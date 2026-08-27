package com.ace.coupon.issuance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.redis.CampaignRedisInitializer;
import com.ace.coupon.redis.CouponEventStatsSnapshot;
import com.ace.coupon.redis.RedisCouponEventStatsReader;
import com.ace.coupon.redis.RedisCouponIssueProcessor;
import com.ace.coupon.repository.CouponEventRepository;
import com.ace.coupon.service.ConfirmFailureRetryService;
import com.ace.coupon.service.CouponEventLifecycleService;
import com.ace.coupon.service.CouponIssueService;

// 확정 실패가 회차를 막고, 재처리가 그것을 풀어 주는지 확인하는 테스트
// 확정 카운터가 올라가지 않으면 pendingQuantity 가 0으로 돌아오지 않고, 회차 상태가 Drain 게이트에 걸려 마감되지 않는다.
@Tag("issuance-accuracy")
@SpringBootTest
@TestPropertySource(properties = {
		// 전환과 재처리 시점을 테스트가 직접 통제
		"coupon.campaign.aggregate-snapshot.enabled=false",
		"coupon.issue.confirm-retry.enabled=false",
		"coupon.issue.persistence.mode=SYNC",
		"spring.jpa.open-in-view=false",
		"spring.jpa.show-sql=false"
})
class ConfirmFailureRetryIntegrationTest {

	private static final int STOCK = 20;

	@MockitoSpyBean
	private RedisCouponIssueProcessor issueProcessor;

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
	private ConfirmFailureRetryService confirmFailureRetryService;

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
		jdbcTemplate.update("DELETE FROM issue_failure_log WHERE event_id = ?", eventId);
		redisTemplate.delete(redisTemplate.keys("coupon:{campaign:" + eventId + "}:*"));
		IssuanceTestFixture.deleteEvent(jdbcTemplate, eventId);
	}

	@Test
	@Timeout(300)
	@DisplayName("확정 실패로 막힌 회차가 재처리 후 마감까지 도달한다")
	void recoversEventBlockedByConfirmFailure() throws Exception {
		givenConfirmAlwaysFails();
		issueUntilSoldOut();

		// 저장은 정상이고 확정X
		assertThat(persistedCount()).isEqualTo(STOCK);
		assertThat(unresolvedConfirmFailures()).isEqualTo(STOCK);

		CouponEventStatsSnapshot beforeRetry = statsReader.read(eventId);
		assertThat(beforeRetry.remainingStock()).isZero();
		assertThat(beforeRetry.confirmedQuantity()).isZero();
		assertThat(beforeRetry.pendingQuantity()).isEqualTo(STOCK);

		// 이 상태로는 마감X -> Drain 게이트가 막는다
		lifecycleService.sweep();
		assertThat(statusOf())
				.as("대기 수량이 남아 있는데 회차가 전환됐습니다")
				.isEqualTo("OPEN");

		// Redis 가 회복되면 재처리가 회수
		givenConfirmRecovered();
		ConfirmFailureRetryService.SweepResult retry =
				confirmFailureRetryService.retryFailedConfirmations();

		assertThat(retry.recovered()).isGreaterThanOrEqualTo(STOCK);
		assertThat(unresolvedConfirmFailures()).isZero();

		CouponEventStatsSnapshot afterRetry = statsReader.read(eventId);
		assertThat(afterRetry.confirmedQuantity()).isEqualTo(STOCK);
		assertThat(afterRetry.pendingQuantity())
				.as("회수했는데 대기 수량이 0으로 돌아오지 않았습니다")
				.isZero();

		// 마감 -> 집계도 함께 확정
		lifecycleService.sweep();
		assertThat(statusOf()).isEqualTo("SOLD_OUT");
		assertThat(issuedQuantity()).isEqualTo(STOCK);
		assertThat(remainingStock()).isZero();
	}

	// 저장은 커밋되고 확정만 실패하는 상태를 만든다
	private void givenConfirmAlwaysFails() {
		doThrow(new RedisConnectionFailureException("확정 실패 유발"))
				.when(issueProcessor).confirm(any(), any(), any());
	}

	private void givenConfirmRecovered() {
		doCallRealMethod().when(issueProcessor).confirm(any(), any(), any());
	}

	private void issueUntilSoldOut() {
		IssuanceTestFixture.ensureUsers(jdbcTemplate, STOCK);
		eventId = IssuanceTestFixture.createEvent(jdbcTemplate, STOCK);
		CouponEvent event = couponEventRepository.findById(eventId).orElseThrow();
		campaignRedisInitializer.initialize(event);

		for (int userId = 1; userId <= STOCK; userId++) {
			couponIssueService.issue(eventId, (long) userId, UUID.randomUUID());
		}
	}

	private long persistedCount() {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM coupon_issue WHERE event_id = ?", Long.class, eventId);
	}

	private long unresolvedConfirmFailures() {
		return jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM issue_failure_log
				WHERE event_id = ? AND failure_stage = 'CONFIRM' AND resolved_at IS NULL
				""", Long.class, eventId);
	}

	private String statusOf() {
		return jdbcTemplate.queryForObject(
				"SELECT status FROM coupon_event WHERE event_id = ?", String.class, eventId);
	}

	private int issuedQuantity() {
		return jdbcTemplate.queryForObject(
				"SELECT issued_quantity FROM coupon_event WHERE event_id = ?", Integer.class, eventId);
	}

	private int remainingStock() {
		return jdbcTemplate.queryForObject(
				"SELECT remaining_stock FROM coupon_event WHERE event_id = ?", Integer.class, eventId);
	}
}
