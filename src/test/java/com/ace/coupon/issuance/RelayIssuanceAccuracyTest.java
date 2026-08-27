package com.ace.coupon.issuance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.AfterEach;
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

// RELAY 경로 정확성 검증
@Tag("issuance-accuracy")
@SpringBootTest
@TestPropertySource(properties = {
		// 백그라운드 스케쥴러가 회차 상태와 집계 컬럼을 바꾸면 검증 결과가 타이밍에 좌우되는 것을 방지
		"coupon.campaign.aggregate-snapshot.enabled=false",
		"coupon.issue.persistence.mode=RELAY",
		"coupon.issue.persistence.refresh-interval=1s",
		"coupon.issue.persistence.block-timeout=100ms",
		"spring.jpa.open-in-view=false",
		"spring.jpa.show-sql=false"
})
class RelayIssuanceAccuracyTest {

	private static final Duration DRAIN_TIMEOUT = Duration.ofMinutes(3);

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

	@AfterEach
	void tearDown() {
		if (eventId == 0) {
			return;
		}
		redisTemplate.delete(redisTemplate.keys("coupon:{campaign:" + eventId + "}:*"));
		IssuanceTestFixture.deleteEvent(jdbcTemplate, eventId);
	}

	private long count(String sql) {
		return jdbcTemplate.queryForObject(sql, Long.class, eventId);
	}

	private long drainUntilStable(long expected) throws InterruptedException {
		Instant deadline = Instant.now().plus(DRAIN_TIMEOUT);
		Instant startedAt = Instant.now();
		while (Instant.now().isBefore(deadline)) {
			if (count("SELECT COUNT(*) FROM coupon_issue WHERE event_id = ?") >= expected) {
				return Duration.between(startedAt, Instant.now()).toMillis();
			}
			Thread.sleep(200);
		}
		return -1;
	}

	@Test
	@Timeout(600)
	@DisplayName("Stream 소비자가 저장해도 SYNC 와 같은 결과에 도달한다")
	void reachesSameResultAsSync() throws Exception {
		int requests = Math.min(IssuanceTestFixture.REQUESTS, 4_000);
		int stock = Math.min(IssuanceTestFixture.STOCK, 2_000);

		eventId = IssuanceTestFixture.createEvent(jdbcTemplate, stock);
		CouponEvent event = couponEventRepository.findById(eventId).orElseThrow();
		campaignRedisInitializer.initialize(event);

		List<Callable<String>> tasks = new ArrayList<>(requests);
		for (int i = 1; i <= requests; i++) {
			long userId = i;
			tasks.add(() -> {
				try {
					couponIssueService.issue(eventId, userId, UUID.randomUUID());
					return "ACCEPTED";
				} catch (CouponException exception) {
					return exception.getErrorCode().name();
				}
			});
		}

		Map<String, Integer> outcomes = IssuanceTestFixture.tally(
				IssuanceTestFixture.runConcurrently(IssuanceTestFixture.THREADS, tasks));

		// 판정은 SYNC 와 동일해야 한다
		assertThat(outcomes.getOrDefault("ACCEPTED", 0)).isEqualTo(stock);
		assertThat(outcomes.getOrDefault("SOLD_OUT", 0)).isEqualTo(requests - stock);

		long drainMillis = drainUntilStable(stock);
		System.out.printf("RELAY 요청 %d / 재고 %d → %s / 드레인 %dms%n",
				requests, stock, outcomes, drainMillis);
		assertThat(drainMillis).as("제한 시간 안에 저장이 따라잡지 못했습니다").isNotNegative();

		// 저장 결과는 SYNC 와 같아야 한다
		assertThat(count("SELECT COUNT(*) FROM coupon_issue WHERE event_id = ?")).isEqualTo(stock);
		assertThat(count("""
				SELECT COUNT(*) FROM coupon_history h JOIN coupon_issue i ON i.issue_id = h.issue_id
				WHERE i.event_id = ?
				""")).isEqualTo(stock);
		assertThat(count("SELECT COUNT(*) FROM issue_failure_log WHERE event_id = ?")).isZero();

		assertThat(count("""
				SELECT COUNT(*) FROM (
				  SELECT user_id FROM coupon_issue WHERE event_id = ?
				  GROUP BY user_id HAVING COUNT(*) > 1) t
				""")).isZero();
		assertThat(count("""
				SELECT COUNT(*) FROM (
				  SELECT issue_sequence FROM coupon_issue WHERE event_id = ?
				  GROUP BY issue_sequence HAVING COUNT(*) > 1) t
				""")).isZero();

		// SYNC 와 다른 점: Stream 엔트리 식별자가 남음
		assertThat(count("""
				SELECT COUNT(*) FROM coupon_issue WHERE event_id = ? AND message_id IS NOT NULL
				""")).isEqualTo(stock);

		// 확정 수 = 저장 수
		// RELAY 는 at-least-once 라 같은 엔트리가 재전달되는데,
		// 확정이 CAS 안에서 카운터를 올리므로 재전달에도 값이 부풀지 않는다
		assertThat(redisTemplate.<String, String>opsForHash()
						.get(CouponRedisKeys.campaign(eventId).metadata(), "confirmedQuantity"))
				.isEqualTo(String.valueOf(stock));

		// 전부 XACK 되어 pending 이 남지 않아야 한다
		assertThat(redisTemplate.<String, String>opsForStream().pending(
						CouponRedisKeys.campaign(eventId).issueStream(), "issue-persist",
						org.springframework.data.domain.Range.unbounded(), 100).size())
				.isZero();
	}
}
