package com.ace.coupon.scheduler;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.ace.coupon.service.CouponEventOpenService;

class CouponEventOpenSchedulerTest {

	private CouponEventOpenService couponEventOpenService;
	private CouponEventOpenScheduler scheduler;

	@BeforeEach
	void setUp() {
		couponEventOpenService = Mockito.mock(CouponEventOpenService.class);
		scheduler = new CouponEventOpenScheduler(couponEventOpenService);
	}

	@Test
	@DisplayName("스케줄러 실행 시 오픈 대상 캠페인 전환을 한 번 요청한다")
	void delegatesOpeningDueEvents() {
		scheduler.openDueEvents();

		verify(couponEventOpenService).openDueEvents();
	}
}
