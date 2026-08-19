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

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.entity.Coupon;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.repository.CouponEventRepository;
import com.ace.coupon.repository.CouponRepository;

class CouponEventCreationPersistenceServiceTest {

	private CouponRepository couponRepository;
	private CouponEventRepository couponEventRepository;
	private CouponEventCreationPersistenceService service;

	@BeforeEach
	void setUp() {
		couponRepository = Mockito.mock(CouponRepository.class);
		couponEventRepository = Mockito.mock(CouponEventRepository.class);
		service = new CouponEventCreationPersistenceService(couponRepository, couponEventRepository);
	}

	@Test
	@DisplayName("캠페인 생성 시 초기 재고와 1인 1매 정책을 저장한다")
	void persistsInitialCampaignState() {
		Coupon coupon = Coupon.builder().id(1L).build();
		LocalDateTime now = LocalDateTime.of(2099, 8, 18, 12, 0);
		LocalDateTime openAt = now.plusDays(1);
		LocalDateTime closeAt = openAt.plusHours(12);
		given(couponRepository.findById(1L)).willReturn(Optional.of(coupon));
		given(couponEventRepository.saveAndFlush(Mockito.any(CouponEvent.class)))
				.willAnswer(invocation -> invocation.getArgument(0));

		service.create(1L, 24, 10_000, openAt, closeAt, CouponEventStatus.SCHEDULED, now);

		ArgumentCaptor<CouponEvent> captor = ArgumentCaptor.forClass(CouponEvent.class);
		Mockito.verify(couponEventRepository).saveAndFlush(captor.capture());
		CouponEvent saved = captor.getValue();
		assertThat(saved.getCoupon()).isSameAs(coupon);
		assertThat(saved.getTotalStock()).isEqualTo(10_000);
		assertThat(saved.getRemainingStock()).isEqualTo(10_000);
		assertThat(saved.getIssuedQuantity()).isZero();
		assertThat(saved.getPerUserLimit()).isOne();
		assertThat(saved.getStatus()).isEqualTo(CouponEventStatus.SCHEDULED);
	}

	@Test
	@DisplayName("존재하지 않는 쿠폰에는 캠페인을 생성하지 않는다")
	void rejectsMissingCoupon() {
		given(couponRepository.findById(99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.create(
				99L, 1, 10_000,
				LocalDateTime.now().plusHours(1),
				LocalDateTime.now().plusHours(2),
				CouponEventStatus.SCHEDULED,
				LocalDateTime.now()))
				.isInstanceOfSatisfying(CouponException.class,
						exception -> assertThat(exception.getErrorCode())
								.isEqualTo(ErrorCode.COUPON_NOT_FOUND));
	}
}
