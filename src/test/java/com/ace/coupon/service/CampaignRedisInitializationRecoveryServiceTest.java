package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.RedisConnectionFailureException;

import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.redis.CampaignInitializationResult;
import com.ace.coupon.redis.CampaignRedisInitializer;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.repository.CouponEventRepository;

class CampaignRedisInitializationRecoveryServiceTest {

	private CouponEventRepository couponEventRepository;
	private CampaignRedisInitializer initializer;
	private CampaignRedisInitializationRecoveryService service;

	@BeforeEach
	void setUp() {
		couponEventRepository = Mockito.mock(CouponEventRepository.class);
		initializer = Mockito.mock(CampaignRedisInitializer.class);
		service = new CampaignRedisInitializationRecoveryService(
				couponEventRepository,
				initializer,
				new CouponIssueRedisProperties(Duration.ofDays(7), ZoneId.of("Asia/Seoul")));
	}

	@Test
	@DisplayName("활성 캠페인 중 Redis 상태가 없는 캠페인을 멱등 초기화한다")
	void recoversActiveCampaigns() {
		CouponEvent missing = event(1L);
		CouponEvent initialized = event(2L);
		given(couponEventRepository.findAllByStatusInAndCloseAtAfter(any(), any()))
				.willReturn(List.of(missing, initialized));
		given(initializer.initialize(missing)).willReturn(CampaignInitializationResult.INITIALIZED);
		given(initializer.initialize(initialized))
				.willReturn(CampaignInitializationResult.ALREADY_INITIALIZED);

		assertThat(service.recoverActiveCampaigns()).isOne();
		verify(initializer).initialize(missing);
		verify(initializer).initialize(initialized);
	}

	@Test
	@DisplayName("한 캠페인의 Redis 장애가 다른 캠페인 복구를 중단시키지 않는다")
	void continuesAfterOneCampaignFails() {
		CouponEvent failed = event(1L);
		CouponEvent recovered = event(2L);
		given(couponEventRepository.findAllByStatusInAndCloseAtAfter(any(), any()))
				.willReturn(List.of(failed, recovered));
		given(initializer.initialize(failed))
				.willThrow(new RedisConnectionFailureException("redis unavailable"));
		given(initializer.initialize(recovered)).willReturn(CampaignInitializationResult.INITIALIZED);

		assertThat(service.recoverActiveCampaigns()).isOne();
		verify(initializer).initialize(recovered);
	}

	private CouponEvent event(long eventId) {
		LocalDateTime now = LocalDateTime.now();
		return CouponEvent.builder()
				.id(eventId)
				.openAt(now.minusMinutes(1))
				.closeAt(now.plusHours(1))
				.totalStock(10_000)
				.remainingStock(10_000)
				.issuedQuantity(0)
				.perUserLimit(1)
				.status(CouponEventStatus.OPEN)
				.createdAt(now)
				.updatedAt(now)
				.build();
	}
}
