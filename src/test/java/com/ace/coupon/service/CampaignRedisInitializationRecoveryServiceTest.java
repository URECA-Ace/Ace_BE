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
import com.ace.coupon.enums.CampaignRedisInitializationStatus;
import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.dto.response.CampaignInitializationResponse;
import com.ace.coupon.redis.CampaignInitializationResult;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.repository.CouponEventRepository;

class CampaignRedisInitializationRecoveryServiceTest {

	private CouponEventRepository couponEventRepository;
	private CampaignAdminService campaignAdminService;
	private CampaignRedisInitializationRecoveryService service;

	@BeforeEach
	void setUp() {
		couponEventRepository = Mockito.mock(CouponEventRepository.class);
		campaignAdminService = Mockito.mock(CampaignAdminService.class);
		service = new CampaignRedisInitializationRecoveryService(
				couponEventRepository,
				campaignAdminService,
				new CouponIssueRedisProperties(Duration.ofDays(7), ZoneId.of("Asia/Seoul")));
	}

	@Test
	@DisplayName("활성 캠페인 중 Redis 상태가 없는 캠페인을 멱등 초기화한다")
	void recoversActiveCampaigns() {
		CouponEvent missing = event(1L);
		given(couponEventRepository.findRedisInitializationRecoveryCandidates(
				any(), any(), any()))
				.willReturn(List.of(missing));
		given(campaignAdminService.initialize(missing))
				.willReturn(response(missing, CampaignInitializationResult.INITIALIZED));

		assertThat(service.recoverActiveCampaigns()).isOne();
		verify(campaignAdminService).initialize(missing);
		verify(couponEventRepository).findRedisInitializationRecoveryCandidates(
				any(), any(), Mockito.eq(CampaignRedisInitializationStatus.INITIALIZED));
	}

	@Test
	@DisplayName("한 캠페인의 Redis 장애가 다른 캠페인 복구를 중단시키지 않는다")
	void continuesAfterOneCampaignFails() {
		CouponEvent failed = event(1L);
		CouponEvent recovered = event(2L);
		given(couponEventRepository.findRedisInitializationRecoveryCandidates(any(), any(), any()))
				.willReturn(List.of(failed, recovered));
		given(campaignAdminService.initialize(failed))
				.willThrow(new RedisConnectionFailureException("redis unavailable"));
		given(campaignAdminService.initialize(recovered))
				.willReturn(response(recovered, CampaignInitializationResult.INITIALIZED));

		assertThat(service.recoverActiveCampaigns()).isOne();
		verify(campaignAdminService).initialize(recovered);
	}

	private CampaignInitializationResponse response(
			CouponEvent event,
			CampaignInitializationResult result) {
		return new CampaignInitializationResponse(
				event.getId(), result, event.getTotalStock(), null, null);
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
