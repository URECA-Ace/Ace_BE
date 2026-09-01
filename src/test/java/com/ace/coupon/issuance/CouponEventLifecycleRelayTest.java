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
import com.ace.consistency.check.StockConsistencyCheck;
import com.ace.consistency.common.ConsistencyVerificationRunner;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResult;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.redis.CampaignRedisInitializer;
import com.ace.coupon.redis.CouponEventStatsSnapshot;
import com.ace.coupon.redis.RedisCouponEventStatsReader;
import com.ace.coupon.repository.CouponEventRepository;
import com.ace.coupon.service.CouponEventLifecycleService;
import com.ace.coupon.service.CouponIssueService;

// RELAY 경로에서 회차 집계 확정과 상태 전환을 검증
@Tag("issuance-accuracy")
@SpringBootTest
@TestPropertySource(properties = {
		// 상태 전환 시점을 테스트가 직접 통제
		"coupon.campaign.aggregate-snapshot.enabled=false",
		"coupon.issue.persistence.mode=RELAY",
		"coupon.issue.persistence.refresh-interval=1s",
		"coupon.issue.persistence.block-timeout=100ms",
		"spring.jpa.open-in-view=false",
		"spring.jpa.show-sql=false"
})
class CouponEventLifecycleRelayTest {

	private static final Duration DRAIN_TIMEOUT = Duration.ofMinutes(3);

	@Autowired
	private CouponIssueService couponIssueService;

	@Autowired
	private CampaignRedisInitializer campaignRedisInitializer;

	@Autowired
	private CouponEventRepository couponEventRepository;

	@Autowired
	private RedisCouponEventStatsReader statsReader;

	@Autowired
	private CouponEventLifecycleService lifecycleService;

	@Autowired
	private ConsistencyVerificationRunner runner;

	@Autowired
	private StockConsistencyCheck stockConsistencyCheck;

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

	@Test
	@Timeout(600)
	@DisplayName("저장이 밀리는 동안 회차를 전환하지 않고, 파이프라인이 빈 뒤 집계를 확정한다")
	void holdsTransitionUntilPipelineIsDrained() throws Exception {
		int requests = Math.min(IssuanceTestFixture.REQUESTS, 4_000);
		int stock = Math.min(IssuanceTestFixture.STOCK, 2_000);
		issueUntilSoldOut(requests, stock);

		// 저장이 밀리는 동안에는 전환하지 않는다
		long pendingBefore = pendingQuantity();
		lifecycleService.sweep();
		long pendingAfter = pendingQuantity();
		boolean gateObserved = pendingBefore > 0 && pendingAfter > 0;
		if (gateObserved) {
			assertThat(statusOf())
					.as("대기 수량이 남아 있는데 회차가 전환됐습니다. pending=%d -> %d", pendingBefore, pendingAfter)
					.isEqualTo("OPEN");
		}

		// 드레인이 끝날 때까지 반복해서 sweep 한다.
		// 전환이 일어난 시점에는 집계가 반드시 실제 저장 건수와 같아야 한다
		long drainMillis = sweepUntilDrained();
		assertThat(drainMillis).as("제한 시간 안에 저장이 따라잡지 못했습니다").isNotNegative();
		System.out.printf("RELAY 요청 %d / 재고 %d / 드레인 %dms / 게이트 관측 %s%n",
				requests, stock, drainMillis, gateObserved);

		// 파이프라인이 빈 뒤에는 집계가 확정되고 회차가 소진 처리된다
		Map<String, Object> aggregate = aggregateOfEvent();
		assertThat(aggregate.get("status")).isEqualTo("SOLD_OUT");
		assertThat(intOf(aggregate, "issued_quantity")).isEqualTo(stock);
		assertThat(intOf(aggregate, "remaining_stock")).isZero();

		// 마감 시각이 지나면 확정된 집계를 유지한 채 마감한다
		// 마감 판정은 DB 의 CURRENT_TIMESTAMP 로 하므로 시각도 DB 쪽에서 만든다
		jdbcTemplate.update(
				"UPDATE coupon_event SET close_at = CURRENT_TIMESTAMP - INTERVAL 1 MINUTE WHERE event_id = ?",
				eventId);
		lifecycleService.sweep();

		Map<String, Object> closed = aggregateOfEvent();
		assertThat(closed.get("status")).isEqualTo("CLOSED");
		assertThat(intOf(closed, "issued_quantity")).isEqualTo(stock);
		assertThat(intOf(closed, "remaining_stock")).isZero();

		// 실제 발급 플로우로 만든 회차가 재고 정합성 검증을 통과한다
		List<VerificationResult> results = runner.run(
				List.of(stockConsistencyCheck), Scope.ofEvent(eventId), TriggerType.ON_DEMAND);
		assertThat(results.get(0).isPass())
				.as("재고 정합성 검증 결과: %s", results.get(0).getDiffDetail())
				.isTrue();
	}

	private void issueUntilSoldOut(int requests, int stock) throws Exception {
		IssuanceTestFixture.ensureUsers(jdbcTemplate, requests);
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
		assertThat(outcomes.getOrDefault("ACCEPTED", 0)).isEqualTo(stock);
	}

	// 드레인이 끝날 때까지 sweep 을 반복하며, 전환된 순간의 집계가 실제 저장 건수와 같은지 확인
	private long sweepUntilDrained() throws InterruptedException {
		Instant startedAt = Instant.now();
		Instant deadline = startedAt.plus(DRAIN_TIMEOUT);
		while (Instant.now().isBefore(deadline)) {
			lifecycleService.sweep();

			Map<String, Object> aggregate = aggregateOfEvent();
			if (!"OPEN".equals(aggregate.get("status"))) {
				assertThat(intOf(aggregate, "issued_quantity"))
						.as("회차가 전환됐는데 집계가 실제 저장 건수와 다릅니다")
						.isEqualTo((int) persistedCount());
				return Duration.between(startedAt, Instant.now()).toMillis();
			}
			Thread.sleep(200);
		}
		return -1;
	}

	private long pendingQuantity() {
		CouponEventStatsSnapshot snapshot = statsReader.read(eventId);
		return snapshot == null ? 0L : snapshot.pendingQuantity();
	}

	private String statusOf() {
		return (String) aggregateOfEvent().get("status");
	}

	private long persistedCount() {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM coupon_issue WHERE event_id = ?", Long.class, eventId);
	}

	private Map<String, Object> aggregateOfEvent() {
		return jdbcTemplate.queryForMap(
				"SELECT status, total_stock, issued_quantity, remaining_stock FROM coupon_event WHERE event_id = ?",
				eventId);
	}

	private int intOf(Map<String, Object> row, String column) {
		return ((Number) row.get(column)).intValue();
	}
}
