package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.redis.CampaignCloseDecision;
import com.ace.coupon.redis.CampaignCloseResult;
import com.ace.coupon.redis.CampaignRedisCloser;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.repository.CouponEventRepository;

@ExtendWith(MockitoExtension.class)
class CouponEventCloseServiceTest {

	private static final Instant CLOSED_AT = Instant.parse("2026-08-27T01:00:00Z");

	@Mock
	private CouponEventRepository couponEventRepository;

	@Mock
	private CampaignRedisCloser campaignRedisCloser;

	private CouponEventCloseService service;

	@BeforeEach
	void setUp() {
		service = new CouponEventCloseService(
				couponEventRepository,
				campaignRedisCloser,
				new CouponIssueRedisProperties(Duration.ofDays(7), ZoneId.of("Asia/Seoul")));
	}

	@Test
	@DisplayName("Redis 발급을 먼저 차단한 뒤 OPEN 행을 CLOSED로 전환한다")
	void closesRedisBeforeDatabaseState() {
		given(couponEventRepository.findById(51L)).willReturn(Optional.of(event(CouponEventStatus.OPEN)));
		given(campaignRedisCloser.close(51L))
				.willReturn(new CampaignCloseDecision(CampaignCloseResult.CLOSED, CLOSED_AT));
		given(couponEventRepository.closeOpenEvent(
				51L, CouponEventStatus.OPEN, CouponEventStatus.CLOSED)).willReturn(1);

		var response = service.close(51L);

		assertThat(response.status()).isEqualTo(CouponEventStatus.CLOSED);
		assertThat(response.closedAt().toInstant()).isEqualTo(CLOSED_AT);
		verify(campaignRedisCloser).close(51L);
		verify(couponEventRepository).closeOpenEvent(
				51L, CouponEventStatus.OPEN, CouponEventStatus.CLOSED);
	}

	@Test
	@DisplayName("Redis 키가 없어도 DB를 마감해 복구 초기화를 차단한다")
	void closesDatabaseWhenRedisWasNotInitialized() {
		given(couponEventRepository.findById(51L)).willReturn(Optional.of(event(CouponEventStatus.OPEN)));
		given(campaignRedisCloser.close(51L))
				.willReturn(new CampaignCloseDecision(CampaignCloseResult.NOT_INITIALIZED, CLOSED_AT));
		given(couponEventRepository.closeOpenEvent(
				51L, CouponEventStatus.OPEN, CouponEventStatus.CLOSED)).willReturn(1);

		assertThat(service.close(51L).status()).isEqualTo(CouponEventStatus.CLOSED);
	}

	@Test
	@DisplayName("SCHEDULED 캠페인은 수동 마감하지 않는다")
	void rejectsScheduledEvent() {
		given(couponEventRepository.findById(51L))
				.willReturn(Optional.of(event(CouponEventStatus.SCHEDULED)));

		assertThatThrownBy(() -> service.close(51L))
				.isInstanceOfSatisfying(CouponException.class,
						exception -> assertThat(exception.getErrorCode())
								.isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
		verify(campaignRedisCloser, never()).close(51L);
	}

	@Test
	@DisplayName("Redis 손상 상태에서는 DB만 마감하지 않는다")
	void rejectsCorruptedRedisState() {
		given(couponEventRepository.findById(51L)).willReturn(Optional.of(event(CouponEventStatus.OPEN)));
		given(campaignRedisCloser.close(51L))
				.willReturn(new CampaignCloseDecision(CampaignCloseResult.CORRUPTED_STATE, CLOSED_AT));

		assertThatThrownBy(() -> service.close(51L))
				.isInstanceOfSatisfying(CouponException.class,
						exception -> assertThat(exception.getErrorCode())
								.isEqualTo(ErrorCode.CAMPAIGN_CLOSE_TEMPORARILY_UNAVAILABLE));
		verify(couponEventRepository, never()).closeOpenEvent(
				51L, CouponEventStatus.OPEN, CouponEventStatus.CLOSED);
	}

	private CouponEvent event(CouponEventStatus status) {
		return CouponEvent.builder()
				.id(51L)
				.openAt(LocalDateTime.of(2026, 8, 27, 9, 0))
				.closeAt(LocalDateTime.of(2026, 8, 28, 9, 0))
				.status(status)
				.build();
	}
}
