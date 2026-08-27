package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.inOrder;

import java.util.List;

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
	@DisplayName("마감 대상을 먼저 닫고 오픈 시각에 도달한 캠페인을 연다")
	void transitionsDueEventsInFailClosedOrder() {
		given(couponEventRepository.closeDueEvents(
				List.of(CouponEventStatus.SCHEDULED, CouponEventStatus.OPEN, CouponEventStatus.SOLD_OUT),
				CouponEventStatus.CLOSED))
				.willReturn(1);
		given(couponEventRepository.openDueEvents(
				CouponEventStatus.SCHEDULED,
				CouponEventStatus.OPEN))
				.willReturn(2);

		var result = service.transitionDueEvents();

		assertThat(result.openedCount()).isEqualTo(2);
		assertThat(result.closedCount()).isOne();
		var order = inOrder(couponEventRepository);
		order.verify(couponEventRepository).closeDueEvents(
				List.of(CouponEventStatus.SCHEDULED, CouponEventStatus.OPEN, CouponEventStatus.SOLD_OUT),
				CouponEventStatus.CLOSED);
		order.verify(couponEventRepository).openDueEvents(
				CouponEventStatus.SCHEDULED,
				CouponEventStatus.OPEN);
	}

	@Test
	@DisplayName("이미 전환된 캠페인만 있으면 추가 상태 변경 없이 0건을 반환한다")
	void returnsZeroWhenNoScheduledEventIsDue() {
		var result = service.transitionDueEvents();

		assertThat(result.openedCount()).isZero();
		assertThat(result.closedCount()).isZero();
	}
}
