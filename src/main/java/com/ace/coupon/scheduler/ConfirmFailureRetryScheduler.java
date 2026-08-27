package com.ace.coupon.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ace.coupon.service.ConfirmFailureRetryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 확정만 실패한 건을 주기적으로 다시 확정
// 회수되지 않으면 그 회차의 pendingQuantity 가 0으로 돌아오지 않아 마감이 막힌다
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
		prefix = "coupon.issue.confirm-retry",
		name = "enabled",
		havingValue = "true")
public class ConfirmFailureRetryScheduler {

	private final ConfirmFailureRetryService confirmFailureRetryService;

	@Scheduled(
			initialDelayString = "${coupon.issue.confirm-retry.initial-delay-ms:10000}",
			fixedDelayString = "${coupon.issue.confirm-retry.fixed-delay-ms:300000}")
	public void retryFailedConfirmations() {
		try {
			ConfirmFailureRetryService.SweepResult result =
					confirmFailureRetryService.retryFailedConfirmations();
			if (result.recovered() > 0) {
				log.info("확정 실패를 회수했습니다. recovered={}, scanned={}",
						result.recovered(), result.scanned());
			}
		} catch (Exception exception) {
			// 예외를 전파하면 다음 주기가 돌지 않는다
			log.error("확정 실패 재처리 실행에 실패했습니다.", exception);
		}
	}
}
