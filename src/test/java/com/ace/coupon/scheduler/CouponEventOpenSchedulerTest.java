package com.ace.coupon.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.scheduling.annotation.Scheduled;

import com.ace.coupon.service.CouponEventOpenService;
import com.ace.coupon.service.CouponEventOpenService.TransitionResult;

class CouponEventOpenSchedulerTest {

	private CouponEventOpenService couponEventOpenService;
	private CouponEventOpenScheduler scheduler;

	@BeforeEach
	void setUp() {
		couponEventOpenService = Mockito.mock(CouponEventOpenService.class);
		scheduler = new CouponEventOpenScheduler(couponEventOpenService);
	}

	@Test
	@DisplayName("스케줄러 실행 시 예약 오픈과 자동 마감 전환을 한 번 요청한다")
	void delegatesDueEventTransitions() {
		Mockito.when(couponEventOpenService.transitionDueEvents())
				.thenReturn(new TransitionResult(1, 1));

		scheduler.transitionDueEvents();

		verify(couponEventOpenService).transitionDueEvents();
	}

	@Test
	@DisplayName("상태 전환은 매분 1초와 31초에 실행한다")
	void runsAtAlignedThirtySecondBoundaries() throws Exception {
		Method method = CouponEventOpenScheduler.class.getDeclaredMethod("transitionDueEvents");
		Scheduled scheduled = method.getAnnotation(Scheduled.class);

		assertThat(scheduled.cron())
				.isEqualTo("${coupon.campaign.status-scheduler.cron:1,31 * * * * *}");
		assertThat(scheduled.zone())
				.isEqualTo("${coupon.campaign.status-scheduler.zone:Asia/Seoul}");
	}
}
