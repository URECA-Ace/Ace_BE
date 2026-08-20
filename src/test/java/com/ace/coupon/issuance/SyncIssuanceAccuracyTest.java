package com.ace.coupon.issuance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.ace.common.exception.CouponException;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.redis.CampaignRedisInitializer;
import com.ace.coupon.redis.CouponRedisKeys;
import com.ace.coupon.repository.CouponEventRepository;
import com.ace.coupon.service.CouponIssueService;

// SYNC 경로 정확성 검증
@Tag("issuance-accuracy")
@SpringBootTest
@TestPropertySource(properties = {
		"coupon.issue.persistence.mode=SYNC",
		"spring.jpa.open-in-view=false",
		"spring.jpa.show-sql=false"
})
class SyncIssuanceAccuracyTest {

	@Autowired
	private CouponIssueService couponIssueService;

	@Autowired
	private CampaignRedisInitializer campaignRedisInitializer;

	@Autowired
	private CouponEventRepository couponEventRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StringRedisTemplate redisTemplate;

	private long eventId;

	private void prepare(int stock) {
		eventId = IssuanceTestFixture.createEvent(jdbcTemplate, stock);
		CouponEvent event = couponEventRepository.findById(eventId).orElseThrow();
		campaignRedisInitializer.initialize(event);
	}

	@BeforeEach
	void setUp() {
		eventId = 0;
	}

	@AfterEach
	void tearDown() {
		if (eventId == 0) {
			return;
		}
		redisTemplate.delete(redisTemplate.keys("coupon:{campaign:" + eventId + "}:*"));
		IssuanceTestFixture.deleteEvent(jdbcTemplate, eventId);
	}

	// 발급 성공이면 "ACCEPTED", 거절이면 에러 코드 이름
	private String attempt(long userId, UUID idempotencyKey) {
		try {
			couponIssueService.issue(eventId, userId, idempotencyKey);
			return "ACCEPTED";
		} catch (CouponException exception) {
			return exception.getErrorCode().name();
		}
	}

	private long count(String sql) {
		return jdbcTemplate.queryForObject(sql, Long.class, eventId);
	}

	private long redisApproved() {
		String sequence = redisTemplate.opsForValue()
				.get(CouponRedisKeys.campaign(eventId).sequence());
		return sequence == null ? 0 : Long.parseLong(sequence);
	}

	@Test
	@Timeout(600)
	@DisplayName("재고를 넘겨 몰려도 초과 발급 0건이고, Redis 승인 수와 MySQL 저장 수가 같다")
	void doesNotOverIssue() throws Exception {
		int requests = IssuanceTestFixture.REQUESTS;
		int stock = IssuanceTestFixture.STOCK;
		prepare(stock);

		List<Callable<String>> tasks = new ArrayList<>(requests);
		for (int i = 1; i <= requests; i++) {
			long userId = i;
			tasks.add(() -> attempt(userId, UUID.randomUUID()));
		}

		Instant startedAt = Instant.now();
		Map<String, Integer> outcomes =
				IssuanceTestFixture.tally(IssuanceTestFixture.runConcurrently(
						IssuanceTestFixture.THREADS, tasks));
		System.out.printf("요청 %d / 재고 %d / 소요 %ds → %s%n",
				requests, stock,
				java.time.Duration.between(startedAt, Instant.now()).toSeconds(), outcomes);

		// 승인은 정확히 재고만큼
		assertThat(outcomes.getOrDefault("ACCEPTED", 0)).isEqualTo(stock);
		assertThat(outcomes.getOrDefault("SOLD_OUT", 0)).isEqualTo(requests - stock);
		// 저장 실패가 하나라도 있으면 아래 수치는 의미가 없다
		assertThat(outcomes).doesNotContainKeys("ISSUE_PERSIST_FAILED", "ISSUE_TEMPORARILY_UNAVAILABLE");

		// Redis 승인 == MySQL 저장 == 이력
		assertThat(redisApproved()).isEqualTo(stock);
		assertThat(count("SELECT COUNT(*) FROM coupon_issue WHERE event_id = ?")).isEqualTo(stock);
		assertThat(count("""
				SELECT COUNT(*) FROM coupon_history h JOIN coupon_issue i ON i.issue_id = h.issue_id
				WHERE i.event_id = ?
				""")).isEqualTo(stock);
		assertThat(count("SELECT COUNT(*) FROM issue_failure_log WHERE event_id = ?")).isZero();

		// 남은 재고가 음수면 초과 발급이다
		assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.campaign(eventId).stock()))
				.isEqualTo("0");

		// 1인 1매 위반 0
		assertThat(count("""
				SELECT COUNT(*) FROM (
				  SELECT user_id FROM coupon_issue WHERE event_id = ?
				  GROUP BY user_id HAVING COUNT(*) > 1) t
				""")).isZero();

		// 순번 중복 0, 그리고 1..stock 이 빠짐없이 채워졌다
		assertThat(count("""
				SELECT COUNT(*) FROM (
				  SELECT issue_sequence FROM coupon_issue WHERE event_id = ?
				  GROUP BY issue_sequence HAVING COUNT(*) > 1) t
				""")).isZero();
		assertThat(count("SELECT MAX(issue_sequence) FROM coupon_issue WHERE event_id = ?"))
				.isEqualTo(stock);

		// 이력 없는 발급 0
		assertThat(count("""
				SELECT COUNT(*) FROM coupon_issue i
				LEFT JOIN coupon_history h ON h.issue_id = i.issue_id
				WHERE i.event_id = ? AND h.history_id IS NULL
				""")).isZero();
	}

	@Test
	@Timeout(300)
	@DisplayName("같은 사용자가 동시에 몰려도 1인 1매를 지킨다")
	void enforcesOnePerUser() throws Exception {
		int users = 100;
		int attemptsPerUser = 10;
		prepare(users * attemptsPerUser);

		List<Callable<String>> tasks = new ArrayList<>();
		for (int attempt = 0; attempt < attemptsPerUser; attempt++) {
			for (int user = 1; user <= users; user++) {
				long userId = user;
				tasks.add(() -> attempt(userId, UUID.randomUUID()));
			}
		}

		Map<String, Integer> outcomes =
				IssuanceTestFixture.tally(IssuanceTestFixture.runConcurrently(
						IssuanceTestFixture.THREADS, tasks));
		System.out.printf("사용자 %d × 시도 %d → %s%n", users, attemptsPerUser, outcomes);

		assertThat(outcomes.getOrDefault("ACCEPTED", 0)).isEqualTo(users);
		assertThat(outcomes.getOrDefault("ALREADY_ISSUED", 0))
				.isEqualTo(users * attemptsPerUser - users);
		assertThat(count("SELECT COUNT(*) FROM coupon_issue WHERE event_id = ?")).isEqualTo(users);
		// 재고가 남아 있어야 한다. 중복 요청이 재고를 깎으면 안 된다
		assertThat(redisApproved()).isEqualTo(users);
	}

	@Test
	@Timeout(300)
	@DisplayName("같은 Idempotency-Key 가 동시에 몰려도 한 건만 저장된다")
	void isIdempotentUnderConcurrency() throws Exception {
		prepare(100);
		UUID sharedKey = UUID.randomUUID();

		List<Callable<String>> tasks = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			tasks.add(() -> attempt(777L, sharedKey));
		}

		Map<String, Integer> outcomes =
				IssuanceTestFixture.tally(IssuanceTestFixture.runConcurrently(
						IssuanceTestFixture.THREADS, tasks));
		System.out.printf("같은 키 200회 → %s%n", outcomes);

		// 같은 키는 전부 같은 응답을 받는다
		assertThat(outcomes.keySet()).containsExactly("ACCEPTED");
		assertThat(count("SELECT COUNT(*) FROM coupon_issue WHERE event_id = ?")).isEqualTo(1);
		assertThat(count("""
				SELECT COUNT(*) FROM coupon_history h JOIN coupon_issue i ON i.issue_id = h.issue_id
				WHERE i.event_id = ?
				""")).isEqualTo(1);
		assertThat(redisApproved()).isEqualTo(1);
	}
}
