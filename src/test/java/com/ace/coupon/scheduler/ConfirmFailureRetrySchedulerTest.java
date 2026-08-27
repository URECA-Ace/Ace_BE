package com.ace.coupon.scheduler;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.ace.coupon.service.ConfirmFailureRetryService;

class ConfirmFailureRetrySchedulerTest {

	private ConfirmFailureRetryService confirmFailureRetryService;
	private ConfirmFailureRetryScheduler scheduler;

	@BeforeEach
	void setUp() {
		confirmFailureRetryService = Mockito.mock(ConfirmFailureRetryService.class);
		scheduler = new ConfirmFailureRetryScheduler(confirmFailureRetryService);
	}

	@Test
	@DisplayName("스케쥴러 실행 시 확정 실패 재처리를 한 번 요청한다")
	void delegatesRetry() {
		given(confirmFailureRetryService.retryFailedConfirmations())
				.willReturn(new ConfirmFailureRetryService.SweepResult(
						0, 0, 0, 0, 0, 0, 0L, List.of()));

		scheduler.retryFailedConfirmations();

		verify(confirmFailureRetryService).retryFailedConfirmations();
	}

	@Test
	@DisplayName("재처리가 실패해도 예외를 전파하지 않아 다음 주기가 계속 돈다")
	void doesNotPropagateFailure() {
		given(confirmFailureRetryService.retryFailedConfirmations())
				.willThrow(new IllegalStateException("boom"));

		scheduler.retryFailedConfirmations();

		verify(confirmFailureRetryService).retryFailedConfirmations();
	}
}
