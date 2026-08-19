package com.ace.coupon.issuance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.ace.common.exception.CouponException;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.redis.CampaignRedisInitializer;
import com.ace.coupon.repository.CouponEventRepository;
import com.ace.coupon.service.CouponIssueService;

// 회차 조회 캐시의 효과를 쿼리 수로 재기 위한 테스트 파일
// 테스트 후 제거 예정
@Tag("issuance-accuracy")
class CampaignCacheQueryCountTest {

	private static final int REQUESTS = 2_000;

	abstract static class Fixture {

		@Autowired
		CouponIssueService couponIssueService;

		@Autowired
		CampaignRedisInitializer campaignRedisInitializer;

		@Autowired
		JdbcTemplate jdbcTemplate;

		@Autowired
		StringRedisTemplate redisTemplate;

		@MockitoSpyBean
		CouponEventRepository couponEventRepository;

		long eventId;

		@AfterEach
		void tearDown() {
			if (eventId == 0) {
				return;
			}
			redisTemplate.delete(redisTemplate.keys("coupon:{campaign:" + eventId + "}:*"));
			IssuanceTestFixture.deleteEvent(jdbcTemplate, eventId);
		}

		// 저장 성공 건수와 소요 밀리초
		long[] issueAll() throws Exception {
			eventId = IssuanceTestFixture.createEvent(jdbcTemplate, REQUESTS);
			CouponEvent event = couponEventRepository.findById(eventId).orElseThrow();
			campaignRedisInitializer.initialize(event);
			// 준비 단계 조회는 제외
			org.mockito.Mockito.clearInvocations(couponEventRepository);

			List<Callable<String>> tasks = new ArrayList<>(REQUESTS);
			for (int i = 1; i <= REQUESTS; i++) {
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

			Instant startedAt = Instant.now();
			long accepted = IssuanceTestFixture.runConcurrently(100, tasks).stream()
					.filter("ACCEPTED"::equals)
					.count();
			long elapsed = Duration.between(startedAt, Instant.now()).toMillis();

			assertThat(accepted).isEqualTo(REQUESTS);
			assertThat(jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM coupon_issue WHERE event_id = ?", Long.class, eventId))
					.isEqualTo(REQUESTS);
			return new long[] { accepted, elapsed };
		}
	}

	@Nested
	@SpringBootTest
	@TestPropertySource(properties = {
			"coupon.issue.persistence.mode=SYNC",
			"coupon.issue.persistence.campaign-cache=true",
			"spring.jpa.open-in-view=false",
			"spring.jpa.show-sql=false"
	})
	@DisplayName("캐시 사용")
	class WithCache extends Fixture {

		@Test
		@Timeout(300)
		@DisplayName("회차 조회는 저장 건수와 무관하게 1회다")
		void queriesOnce() throws Exception {
			long[] result = issueAll();

			verify(couponEventRepository, times(1)).findWithCouponById(eventId);
			System.out.printf("[캐시 O] 저장 %d건 / 회차 조회 1회 / %dms%n", result[0], result[1]);
		}
	}

	@Nested
	@SpringBootTest
	@TestPropertySource(properties = {
			"coupon.issue.persistence.mode=SYNC",
			"coupon.issue.persistence.campaign-cache=false",
			"spring.jpa.open-in-view=false",
			"spring.jpa.show-sql=false"
	})
	@DisplayName("캐시 미사용")
	class WithoutCache extends Fixture {

		@Test
		@Timeout(300)
		@DisplayName("회차 조회가 저장 건수만큼 늘어난다")
		void queriesPerSave() throws Exception {
			long[] result = issueAll();

			verify(couponEventRepository, times(REQUESTS)).findWithCouponById(eventId);
			verify(couponEventRepository, atLeast(1)).findWithCouponById(any());
			System.out.printf("[캐시 X] 저장 %d건 / 회차 조회 %d회 / %dms%n",
					result[0], REQUESTS, result[1]);
		}
	}
}
