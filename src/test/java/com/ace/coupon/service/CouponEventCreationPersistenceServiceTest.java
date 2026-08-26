package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.entity.CampaignRedisInitialization;
import com.ace.coupon.entity.Coupon;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.repository.CouponEventRepository;
import com.ace.coupon.repository.CampaignRedisInitializationRepository;
import com.ace.coupon.repository.CouponRepository;

class CouponEventCreationPersistenceServiceTest {

	private CouponRepository couponRepository;
	private CouponEventRepository couponEventRepository;
	private CampaignRedisInitializationRepository initializationRepository;
	private CouponEventCreationPersistenceService service;

	@BeforeEach
	void setUp() {
		couponRepository = Mockito.mock(CouponRepository.class);
		couponEventRepository = Mockito.mock(CouponEventRepository.class);
		initializationRepository = Mockito.mock(CampaignRedisInitializationRepository.class);
		service = new CouponEventCreationPersistenceService(
				couponRepository, couponEventRepository, initializationRepository);
	}

	@Test
	@DisplayName("캠페인 생성 시 초기 재고와 1인 1매 정책을 저장한다")
	void persistsInitialCampaignState() {
		Coupon coupon = Coupon.builder().id(1L).build();
		LocalDateTime now = LocalDateTime.of(2099, 8, 18, 12, 0);
		LocalDateTime openAt = now.plusDays(1);
		LocalDateTime closeAt = openAt.plusHours(12);
		given(couponRepository.findByIdForUpdate(1L)).willReturn(Optional.of(coupon));
		given(couponEventRepository.findByCoupon_IdAndRound(1L, 24))
				.willReturn(Optional.empty());
		given(couponEventRepository.saveAndFlush(Mockito.any(CouponEvent.class)))
				.willAnswer(invocation -> {
					CouponEvent event = invocation.getArgument(0);
					ReflectionTestUtils.setField(event, "id", 10L);
					return event;
				});

		service.createOrReuse(
				1L, 24, 10_000, openAt, closeAt, CouponEventStatus.SCHEDULED, now);

		ArgumentCaptor<CouponEvent> captor = ArgumentCaptor.forClass(CouponEvent.class);
		Mockito.verify(couponEventRepository).saveAndFlush(captor.capture());
		CouponEvent saved = captor.getValue();
		assertThat(saved.getCoupon()).isSameAs(coupon);
		assertThat(saved.getTotalStock()).isEqualTo(10_000);
		assertThat(saved.getRemainingStock()).isEqualTo(10_000);
		assertThat(saved.getIssuedQuantity()).isZero();
		assertThat(saved.getPerUserLimit()).isOne();
		assertThat(saved.getStatus()).isEqualTo(CouponEventStatus.SCHEDULED);

		ArgumentCaptor<CampaignRedisInitialization> initializationCaptor =
				ArgumentCaptor.forClass(CampaignRedisInitialization.class);
		Mockito.verify(initializationRepository).save(initializationCaptor.capture());
		CampaignRedisInitialization initialization = initializationCaptor.getValue();
		assertThat(initialization.getEventId()).isEqualTo(10L);
		assertThat(initialization.getStatus().name()).isEqualTo("PENDING");
		assertThat(initialization.getAttemptCount()).isZero();
		Mockito.verify(couponRepository).findByIdForUpdate(1L);
	}

	@Test
	@DisplayName("명시 회차의 동일 설정 재요청은 쿠폰 행 락 안에서 기존 캠페인을 재사용한다")
	void reusesExplicitRoundWhileCouponIsLocked() {
		Coupon coupon = Coupon.builder().id(1L).build();
		LocalDateTime now = LocalDateTime.of(2099, 8, 18, 12, 0);
		LocalDateTime openAt = now.plusHours(1);
		LocalDateTime closeAt = now.plusHours(2);
		CouponEvent existing = event(coupon, 24, 10_000, openAt, closeAt, now);
		given(couponRepository.findByIdForUpdate(1L)).willReturn(Optional.of(coupon));
		given(couponEventRepository.findByCoupon_IdAndRound(1L, 24))
				.willReturn(Optional.of(existing));

		CouponEvent result = service.createOrReuse(
				1L, 24, 10_000, openAt, closeAt, CouponEventStatus.SCHEDULED, now);

		assertThat(result).isSameAs(existing);
		Mockito.verify(couponRepository).findByIdForUpdate(1L);
		Mockito.verify(couponEventRepository, Mockito.never())
				.saveAndFlush(Mockito.any(CouponEvent.class));
	}

	@Test
	@DisplayName("명시 회차의 설정이 다르면 쿠폰 행 락 안에서 409 비즈니스 예외를 발생시킨다")
	void rejectsConflictingExplicitRoundWhileCouponIsLocked() {
		Coupon coupon = Coupon.builder().id(1L).build();
		LocalDateTime now = LocalDateTime.of(2099, 8, 18, 12, 0);
		LocalDateTime openAt = now.plusHours(1);
		LocalDateTime closeAt = now.plusHours(2);
		CouponEvent existing = event(coupon, 24, 5_000, openAt, closeAt, now);
		given(couponRepository.findByIdForUpdate(1L)).willReturn(Optional.of(coupon));
		given(couponEventRepository.findByCoupon_IdAndRound(1L, 24))
				.willReturn(Optional.of(existing));

		assertThatThrownBy(() -> service.createOrReuse(
				1L, 24, 10_000, openAt, closeAt, CouponEventStatus.SCHEDULED, now))
				.isInstanceOfSatisfying(CouponException.class,
						exception -> assertThat(exception.getErrorCode())
								.isEqualTo(ErrorCode.EVENT_CONFIGURATION_CONFLICT));
	}

	@Test
	@DisplayName("쿠폰 행을 잠그고 마지막 회차의 다음 번호를 배정한다")
	void assignsNextRoundWhileCouponIsLocked() {
		Coupon coupon = Coupon.builder().id(1L).build();
		LocalDateTime now = LocalDateTime.of(2099, 8, 18, 12, 0);
		given(couponRepository.findByIdForUpdate(1L)).willReturn(Optional.of(coupon));
		given(couponEventRepository
				.findFirstByCoupon_IdAndTotalStockAndOpenAtAndCloseAtAndPerUserLimitOrderByIdAsc(
						1L, 10_000, now.plusHours(1), now.plusHours(2), 1))
				.willReturn(Optional.empty());
		given(couponEventRepository.findMaxRoundByCouponId(1L)).willReturn(2);
		given(couponEventRepository.saveAndFlush(Mockito.any(CouponEvent.class)))
				.willAnswer(invocation -> {
					CouponEvent event = invocation.getArgument(0);
					ReflectionTestUtils.setField(event, "id", 11L);
					return event;
				});

		CouponEvent saved = service.createNextRoundOrReuse(
				1L, 10_000, now.plusHours(1), now.plusHours(2),
				CouponEventStatus.SCHEDULED, now);

		assertThat(saved.getRound()).isEqualTo(3);
		Mockito.verify(couponRepository).findByIdForUpdate(1L);
		Mockito.verify(couponEventRepository).findMaxRoundByCouponId(1L);
	}

	@Test
	@DisplayName("자동 회차 재요청은 동일 설정 캠페인을 재사용해 새 회차를 만들지 않는다")
	void reusesAutomaticallyCreatedCampaignByConfiguration() {
		Coupon coupon = Coupon.builder().id(1L).build();
		LocalDateTime now = LocalDateTime.of(2099, 8, 18, 12, 0);
		LocalDateTime openAt = now.plusHours(1);
		LocalDateTime closeAt = now.plusHours(2);
		CouponEvent existing = event(coupon, 3, 10_000, openAt, closeAt, now);
		given(couponRepository.findByIdForUpdate(1L)).willReturn(Optional.of(coupon));
		given(couponEventRepository
				.findFirstByCoupon_IdAndTotalStockAndOpenAtAndCloseAtAndPerUserLimitOrderByIdAsc(
						1L, 10_000, openAt, closeAt, 1))
				.willReturn(Optional.of(existing));

		CouponEvent result = service.createNextRoundOrReuse(
				1L, 10_000, openAt, closeAt, CouponEventStatus.SCHEDULED, now);

		assertThat(result).isSameAs(existing);
		Mockito.verify(couponEventRepository, Mockito.never()).findMaxRoundByCouponId(1L);
		Mockito.verify(couponEventRepository, Mockito.never())
				.saveAndFlush(Mockito.any(CouponEvent.class));
	}

	@Test
	@DisplayName("존재하지 않는 쿠폰에는 캠페인을 생성하지 않는다")
	void rejectsMissingCoupon() {
		given(couponRepository.findByIdForUpdate(99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.createOrReuse(
				99L, 1, 10_000,
				LocalDateTime.now().plusHours(1),
				LocalDateTime.now().plusHours(2),
				CouponEventStatus.SCHEDULED,
				LocalDateTime.now()))
				.isInstanceOfSatisfying(CouponException.class,
						exception -> assertThat(exception.getErrorCode())
								.isEqualTo(ErrorCode.COUPON_NOT_FOUND));
	}

	private CouponEvent event(
			Coupon coupon,
			int round,
			int totalStock,
			LocalDateTime openAt,
			LocalDateTime closeAt,
			LocalDateTime now) {
		return CouponEvent.builder()
				.id(10L)
				.coupon(coupon)
				.round(round)
				.openAt(openAt)
				.closeAt(closeAt)
				.totalStock(totalStock)
				.remainingStock(totalStock)
				.issuedQuantity(0)
				.perUserLimit(1)
				.status(CouponEventStatus.SCHEDULED)
				.createdAt(now)
				.updatedAt(now)
				.build();
	}
}
