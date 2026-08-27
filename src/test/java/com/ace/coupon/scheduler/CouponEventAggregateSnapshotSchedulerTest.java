package com.ace.coupon.scheduler;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import com.ace.coupon.service.CouponEventAggregateSnapshotService;
import com.ace.coupon.service.CouponEventLifecycleService;

class CouponEventAggregateSnapshotSchedulerTest {

	private CouponEventAggregateSnapshotService snapshotService;
	private CouponEventLifecycleService lifecycleService;
	private CouponEventAggregateSnapshotScheduler scheduler;

	@BeforeEach
	void setUp() {
		snapshotService = Mockito.mock(CouponEventAggregateSnapshotService.class);
		lifecycleService = Mockito.mock(CouponEventLifecycleService.class);
		scheduler = new CouponEventAggregateSnapshotScheduler(snapshotService, lifecycleService);

		given(snapshotService.snapshotActiveEvents()).willReturn(sweep());
		given(lifecycleService.sweep()).willReturn(lifecycleSweep());
	}

	@Test
	@DisplayName("집계 갱신을 먼저 하고 상태 전환을 이어서 실행한다")
	void snapshotsBeforeAdvancingStatus() {
		scheduler.snapshotAndAdvanceEvents();

		InOrder inOrder = Mockito.inOrder(snapshotService, lifecycleService);
		inOrder.verify(snapshotService).snapshotActiveEvents();
		inOrder.verify(lifecycleService).sweep();
	}

	@Test
	@DisplayName("집계 갱신이 실패해도 상태 전환은 실행한다")
	void advancesStatusEvenWhenSnapshotFails() {
		given(snapshotService.snapshotActiveEvents()).willThrow(new IllegalStateException("boom"));

		scheduler.snapshotAndAdvanceEvents();

		verify(lifecycleService).sweep();
	}

	@Test
	@DisplayName("상태 전환이 실패해도 예외를 전파하지 않아 다음 주기가 계속 돈다")
	void doesNotPropagateFailureFromStatusTransition() {
		given(lifecycleService.sweep()).willThrow(new IllegalStateException("boom"));

		scheduler.snapshotAndAdvanceEvents();

		verify(snapshotService).snapshotActiveEvents();
	}

	private CouponEventAggregateSnapshotService.SweepResult sweep() {
		return new CouponEventAggregateSnapshotService.SweepResult(0, 0, 0, 0, 0, 0);
	}

	private CouponEventLifecycleService.SweepResult lifecycleSweep() {
		return new CouponEventLifecycleService.SweepResult(0, 0, 0, 0);
	}
}
