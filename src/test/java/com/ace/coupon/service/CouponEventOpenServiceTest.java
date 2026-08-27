package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.repository.CouponEventRepository;

class CouponEventOpenServiceTest {

	private CouponEventRepository couponEventRepository;
	private CouponEventOpenService service;

	@BeforeEach
	void setUp() {
		couponEventRepository = Mockito.mock(CouponEventRepository.class);
		service = new CouponEventOpenService(couponEventRepository);
	}

	@Test
	@DisplayName("오픈 시각에 도달한 SCHEDULED 캠페인을 OPEN으로 전환한다")
	void opensDueEvents() {
		given(couponEventRepository.openDueEvents(
				CouponEventStatus.SCHEDULED,
				CouponEventStatus.OPEN))
				.willReturn(2);

		int openedCount = service.openDueEvents();

		assertThat(openedCount).isEqualTo(2);
		verify(couponEventRepository).openDueEvents(
				CouponEventStatus.SCHEDULED,
				CouponEventStatus.OPEN);
	}

	@Test
	@DisplayName("이미 전환된 캠페인만 있으면 추가 상태 변경 없이 0건을 반환한다")
	void returnsZeroWhenNoScheduledEventIsDue() {
		given(couponEventRepository.openDueEvents(
				CouponEventStatus.SCHEDULED,
				CouponEventStatus.OPEN))
				.willReturn(0);

		assertThat(service.openDueEvents()).isZero();
	}
}
