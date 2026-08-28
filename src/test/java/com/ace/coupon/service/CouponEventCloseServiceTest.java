package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
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
import com.ace.coupon.service.CouponEventLifecycleService.CloseAttempt;

@ExtendWith(MockitoExtension.class)
class CouponEventCloseServiceTest {

	private static final Instant CLOSED_AT = Instant.parse("2026-08-27T01:00:00Z");
	private static final LocalDateTime CLOSED_AT_SEOUL =
			LocalDateTime.ofInstant(CLOSED_AT, ZoneId.of("Asia/Seoul"));

	@Mock
	private CouponEventRepository couponEventRepository;

	@Mock
	private CampaignRedisCloser campaignRedisCloser;

	@Mock
	private CouponEventLifecycleService lifecycleService;

	private CouponEventCloseService service;

	@BeforeEach
	void setUp() {
		service = new CouponEventCloseService(
				couponEventRepository,
				campaignRedisCloser,
				lifecycleService,
				new CouponIssueRedisProperties(Duration.ofDays(7), ZoneId.of("Asia/Seoul")));
	}

	@Test
	@DisplayName("Redis 발급을 차단하고 마감 시각을 당긴 뒤, Drain 이 끝났으면 CLOSED로 전환한다")
	void closesEventWhenPipelineIsDrained() {
		given(couponEventRepository.findById(51L))
				.willReturn(Optional.of(event(CouponEventStatus.OPEN)))
				.willReturn(Optional.of(event(CouponEventStatus.CLOSED)));
		given(campaignRedisCloser.close(51L))
				.willReturn(new CampaignCloseDecision(CampaignCloseResult.CLOSED, CLOSED_AT));
		given(lifecycleService.closeIfDrained(51L)).willReturn(CloseAttempt.CLOSED);

		var response = service.close(51L);

		assertThat(response.status()).isEqualTo(CouponEventStatus.CLOSED);
		assertThat(response.drained()).isTrue();
		assertThat(response.closedAt().toInstant()).isEqualTo(CLOSED_AT);
		verify(campaignRedisCloser).close(51L);
		// 상태는 Drain 을 확인하는 경로로만 바뀐다. 마감 시각만 당긴다
		verify(couponEventRepository).advanceCloseAt(
				51L,
				List.of(CouponEventStatus.OPEN, CouponEventStatus.SOLD_OUT),
				CLOSED_AT_SEOUL);
	}

	@Test
	@DisplayName("확정 대기 건이 남아 있으면 발급만 차단하고 상태는 그대로 둔다")
	void keepsStatusWhenPipelineIsNotDrained() {
		given(couponEventRepository.findById(51L))
				.willReturn(Optional.of(event(CouponEventStatus.OPEN)));
		given(campaignRedisCloser.close(51L))
				.willReturn(new CampaignCloseDecision(CampaignCloseResult.CLOSED, CLOSED_AT));
		given(lifecycleService.closeIfDrained(51L)).willReturn(CloseAttempt.WAITING_FOR_DRAIN);

		var response = service.close(51L);

		// 발급은 이미 Redis 에서 막혔고, 남은 건이 확정되면 주기 sweep 이 마감한다
		assertThat(response.status()).isEqualTo(CouponEventStatus.OPEN);
		assertThat(response.drained()).isFalse();
		assertThat(response.closedAt().toInstant()).isEqualTo(CLOSED_AT);
	}

	@Test
	@DisplayName("Redis 키가 없으면 DB 마감 시각을 변경하지 않고 재시도 가능 오류를 반환한다")
	void rejectsCloseWhenRedisWasNotInitialized() {
		given(couponEventRepository.findById(51L))
				.willReturn(Optional.of(event(CouponEventStatus.OPEN)));
		given(campaignRedisCloser.close(51L))
				.willReturn(new CampaignCloseDecision(CampaignCloseResult.NOT_INITIALIZED, CLOSED_AT));

		assertThatThrownBy(() -> service.close(51L))
				.isInstanceOfSatisfying(CouponException.class,
						exception -> assertThat(exception.getErrorCode())
								.isEqualTo(ErrorCode.CAMPAIGN_CLOSE_TEMPORARILY_UNAVAILABLE));
		verify(couponEventRepository, never()).advanceCloseAt(anyLong(), any(), any());
		verify(lifecycleService, never()).closeIfDrained(anyLong());
	}

	@Test
	@DisplayName("반복 마감은 Redis에 저장된 기존 마감 시각을 반환한다")
	void returnsExistingCloseAtForRepeatedClose() {
		Instant existingCloseAt = CLOSED_AT.minusSeconds(30);
		given(couponEventRepository.findById(51L))
				.willReturn(Optional.of(event(CouponEventStatus.OPEN)));
		given(campaignRedisCloser.close(51L))
				.willReturn(new CampaignCloseDecision(
						CampaignCloseResult.ALREADY_CLOSED, existingCloseAt));
		given(lifecycleService.closeIfDrained(51L)).willReturn(CloseAttempt.WAITING_FOR_DRAIN);

		var response = service.close(51L);

		assertThat(response.closedAt().toInstant()).isEqualTo(existingCloseAt);
		verify(couponEventRepository).advanceCloseAt(
				51L,
				List.of(CouponEventStatus.OPEN, CouponEventStatus.SOLD_OUT),
				LocalDateTime.ofInstant(existingCloseAt, ZoneId.of("Asia/Seoul")));
	}

	@Test
	@DisplayName("SOLD_OUT 회차도 수동 마감할 수 있다")
	void allowsSoldOutEvent() {
		given(couponEventRepository.findById(51L))
				.willReturn(Optional.of(event(CouponEventStatus.SOLD_OUT)))
				.willReturn(Optional.of(event(CouponEventStatus.CLOSED)));
		given(campaignRedisCloser.close(51L))
				.willReturn(new CampaignCloseDecision(CampaignCloseResult.CLOSED, CLOSED_AT));
		given(lifecycleService.closeIfDrained(51L)).willReturn(CloseAttempt.CLOSED);

		assertThat(service.close(51L).status()).isEqualTo(CouponEventStatus.CLOSED);
	}

	@Test
	@DisplayName("이미 마감된 회차는 Redis 를 건드리지 않고 그대로 돌려준다")
	void returnsClosedEventWithoutTouchingRedis() {
		given(couponEventRepository.findById(51L))
				.willReturn(Optional.of(event(CouponEventStatus.CLOSED)));

		var response = service.close(51L);

		assertThat(response.status()).isEqualTo(CouponEventStatus.CLOSED);
		assertThat(response.drained()).isTrue();
		verify(campaignRedisCloser, never()).close(51L);
		verify(couponEventRepository, never()).advanceCloseAt(anyLong(), any(), any());
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
	@DisplayName("Redis 손상 상태에서는 마감 시각도 당기지 않는다")
	void rejectsCorruptedRedisState() {
		given(couponEventRepository.findById(51L))
				.willReturn(Optional.of(event(CouponEventStatus.OPEN)));
		given(campaignRedisCloser.close(51L))
				.willReturn(new CampaignCloseDecision(CampaignCloseResult.CORRUPTED_STATE, CLOSED_AT));

		assertThatThrownBy(() -> service.close(51L))
				.isInstanceOfSatisfying(CouponException.class,
						exception -> assertThat(exception.getErrorCode())
								.isEqualTo(ErrorCode.CAMPAIGN_CLOSE_TEMPORARILY_UNAVAILABLE));
		verify(couponEventRepository, never()).advanceCloseAt(anyLong(), any(), any());
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
