package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.request.CouponEventCreateRequest;
import com.ace.coupon.dto.response.CouponEventCreateResponse;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.repository.CouponEventRepository;

class CouponEventCreationServiceTest {

	private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");
	private static final OffsetDateTime OPEN_AT =
			OffsetDateTime.parse("2099-08-19T10:00:00+09:00");
	private static final OffsetDateTime CLOSE_AT =
			OffsetDateTime.parse("2099-08-19T23:59:59+09:00");

	private CouponEventCreationPersistenceService persistenceService;
	private CouponEventRepository couponEventRepository;
	private CampaignAdminService campaignAdminService;
	private CouponEventCreationService service;

	@BeforeEach
	void setUp() {
		persistenceService = Mockito.mock(CouponEventCreationPersistenceService.class);
		couponEventRepository = Mockito.mock(CouponEventRepository.class);
		campaignAdminService = Mockito.mock(CampaignAdminService.class);
		service = new CouponEventCreationService(
				persistenceService,
				couponEventRepository,
				campaignAdminService,
				new CouponIssueRedisProperties(Duration.ofDays(7), ZONE_ID));
	}

	@Test
	@DisplayName("DB 커밋으로 식별자를 얻은 캠페인을 Redis에 초기화한다")
	void persistsThenInitializesCampaign() {
		CouponEvent event = event(24L, 10_000, OPEN_AT, CLOSE_AT);
		given(persistenceService.create(
				ArgumentMatchers.eq(1L), ArgumentMatchers.eq(24), ArgumentMatchers.eq(10_000),
				ArgumentMatchers.any(), ArgumentMatchers.any(),
				ArgumentMatchers.eq(CouponEventStatus.SCHEDULED), ArgumentMatchers.any()))
				.willReturn(event);
		CouponEventCreateResponse response = service.create(1L, request(10_000, OPEN_AT, CLOSE_AT));

		assertThat(response.eventId()).isEqualTo(24L);
		assertThat(response.remainingStock()).isEqualTo(10_000);
		assertThat(response.perUserLimit()).isOne();
		verify(campaignAdminService).initialize(event);
	}

	@Test
	@DisplayName("회차를 생략하면 DB에서 다음 회차를 배정하고 Redis를 초기화한다")
	void assignsNextRoundWhenRoundIsMissing() {
		CouponEvent event = event(25L, 10_000, OPEN_AT, CLOSE_AT);
		given(persistenceService.createNextRound(
				ArgumentMatchers.eq(1L), ArgumentMatchers.eq(10_000),
				ArgumentMatchers.any(), ArgumentMatchers.any(),
				ArgumentMatchers.eq(CouponEventStatus.SCHEDULED), ArgumentMatchers.any()))
				.willReturn(event);

		CouponEventCreateResponse response = service.create(
				1L, new CouponEventCreateRequest(null, 10_000, OPEN_AT, CLOSE_AT));

		assertThat(response.eventId()).isEqualTo(25L);
		verify(persistenceService).createNextRound(
				ArgumentMatchers.eq(1L), ArgumentMatchers.eq(10_000),
				ArgumentMatchers.any(), ArgumentMatchers.any(),
				ArgumentMatchers.eq(CouponEventStatus.SCHEDULED), ArgumentMatchers.any());
		verify(campaignAdminService).initialize(event);
	}

	@Test
	@DisplayName("동일 회차의 동일 설정 재요청은 기존 캠페인으로 Redis 초기화를 재시도한다")
	void retriesInitializationForIdenticalCampaign() {
		CouponEvent existing = event(24L, 10_000, OPEN_AT, CLOSE_AT);
		given(couponEventRepository.findByCoupon_IdAndRound(1L, 24))
				.willReturn(Optional.of(existing));
		CouponEventCreateResponse response = service.create(1L, request(10_000, OPEN_AT, CLOSE_AT));

		assertThat(response.eventId()).isEqualTo(24L);
		verify(campaignAdminService).initialize(existing);
		verify(persistenceService, never()).create(
				ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
				ArgumentMatchers.any(), ArgumentMatchers.any(),
				ArgumentMatchers.any(), ArgumentMatchers.any());
	}

	@Test
	@DisplayName("동시 생성 UNIQUE 충돌 후 동일 설정 캠페인을 조회해 재사용한다")
	void reusesConcurrentlyCreatedCampaign() {
		CouponEvent existing = event(24L, 10_000, OPEN_AT, CLOSE_AT);
		given(couponEventRepository.findByCoupon_IdAndRound(1L, 24))
				.willReturn(Optional.empty())
				.willReturn(Optional.of(existing));
		given(persistenceService.create(
				ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
				ArgumentMatchers.any(), ArgumentMatchers.any(),
				ArgumentMatchers.any(), ArgumentMatchers.any()))
				.willThrow(new DataIntegrityViolationException("duplicate"));
		CouponEventCreateResponse response = service.create(1L, request(10_000, OPEN_AT, CLOSE_AT));

		assertThat(response.eventId()).isEqualTo(24L);
		verify(campaignAdminService).initialize(existing);
	}

	@Test
	@DisplayName("동일 회차에 다른 설정이 존재하면 재고 덮어쓰기를 거절한다")
	void rejectsDifferentConfiguration() {
		CouponEvent existing = event(24L, 5_000, OPEN_AT, CLOSE_AT);
		given(couponEventRepository.findByCoupon_IdAndRound(1L, 24))
				.willReturn(Optional.of(existing));

		assertThatThrownBy(() -> service.create(1L, request(10_000, OPEN_AT, CLOSE_AT)))
				.isInstanceOfSatisfying(CouponException.class,
						exception -> assertThat(exception.getErrorCode())
								.isEqualTo(ErrorCode.EVENT_CONFIGURATION_CONFLICT));
		verify(campaignAdminService, never()).initialize(ArgumentMatchers.any(CouponEvent.class));
	}

	@Test
	@DisplayName("DB 저장 후 Redis 장애가 발생하면 재시도 가능한 503 오류로 변환한다")
	void returnsUnavailableWhenRedisInitializationFails() {
		CouponEvent event = event(24L, 10_000, OPEN_AT, CLOSE_AT);
		given(persistenceService.create(
				ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
				ArgumentMatchers.any(), ArgumentMatchers.any(),
				ArgumentMatchers.any(), ArgumentMatchers.any()))
				.willReturn(event);
		given(campaignAdminService.initialize(event))
				.willThrow(new RedisConnectionFailureException("redis unavailable"));

		assertThatThrownBy(() -> service.create(1L, request(10_000, OPEN_AT, CLOSE_AT)))
				.isInstanceOfSatisfying(CouponException.class,
						exception -> assertThat(exception.getErrorCode()).isEqualTo(
								ErrorCode.CAMPAIGN_INITIALIZATION_TEMPORARILY_UNAVAILABLE));
	}

	@Test
	@DisplayName("공통 초기화 경로의 실패도 생성 API에서는 재시도 가능한 503으로 변환한다")
	void returnsUnavailableWhenInitializerReportsWriteFailure() {
		CouponEvent event = event(24L, 10_000, OPEN_AT, CLOSE_AT);
		given(persistenceService.create(
				ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
				ArgumentMatchers.any(), ArgumentMatchers.any(),
				ArgumentMatchers.any(), ArgumentMatchers.any()))
				.willReturn(event);
		given(campaignAdminService.initialize(event))
				.willThrow(new CouponException(ErrorCode.CAMPAIGN_INIT_FAILED));

		assertThatThrownBy(() -> service.create(1L, request(10_000, OPEN_AT, CLOSE_AT)))
				.isInstanceOfSatisfying(CouponException.class,
						exception -> assertThat(exception.getErrorCode()).isEqualTo(
								ErrorCode.CAMPAIGN_INITIALIZATION_TEMPORARILY_UNAVAILABLE));
	}

	@Test
	@DisplayName("오픈 시각이 마감 시각보다 늦으면 DB 저장 전에 거절한다")
	void rejectsInvalidPeriodBeforePersistence() {
		assertThatThrownBy(() -> service.create(1L, request(10_000, CLOSE_AT, OPEN_AT)))
				.isInstanceOfSatisfying(CouponException.class,
						exception -> assertThat(exception.getErrorCode())
								.isEqualTo(ErrorCode.INVALID_REQUEST));
		verify(persistenceService, never()).create(
				ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
				ArgumentMatchers.any(), ArgumentMatchers.any(),
				ArgumentMatchers.any(), ArgumentMatchers.any());
	}

	private CouponEventCreateRequest request(
			int totalStock,
			OffsetDateTime openAt,
			OffsetDateTime closeAt) {
		return new CouponEventCreateRequest(24, totalStock, openAt, closeAt);
	}

	private CouponEvent event(
			long eventId,
			int totalStock,
			OffsetDateTime openAt,
			OffsetDateTime closeAt) {
		LocalDateTime createdAt = LocalDateTime.of(2099, 8, 18, 12, 0);
		return CouponEvent.builder()
				.id(eventId)
				.round(24)
				.openAt(LocalDateTime.ofInstant(openAt.toInstant(), ZONE_ID))
				.closeAt(LocalDateTime.ofInstant(closeAt.toInstant(), ZONE_ID))
				.totalStock(totalStock)
				.remainingStock(totalStock)
				.issuedQuantity(0)
				.perUserLimit(1)
				.status(CouponEventStatus.SCHEDULED)
				.createdAt(createdAt)
				.updatedAt(createdAt)
				.build();
	}
}
