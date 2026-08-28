package com.ace.coupon.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ace.coupon.service.CompensationFailureRetryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 보상(재고 원복)에 실패한 건을 주기적으로 다시 원복
// 되돌리지 못하면 재고가 Redis 에 묶여 그 회차의 pendingQuantity 가 0으로 돌아오지 않고 마감이 막힌다
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
		prefix = "coupon.issue.compensation-retry",
		name = "enabled",
		havingValue = "true")
public class CompensationFailureRetryScheduler {

	private final CompensationFailureRetryService compensationFailureRetryService;

	@Scheduled(
			initialDelayString = "${coupon.issue.compensation-retry.initial-delay-ms:10000}",
			fixedDelayString = "${coupon.issue.compensation-retry.fixed-delay-ms:60000}")
	public void retryFailedCompensations() {
		try {
			CompensationFailureRetryService.SweepResult result =
					compensationFailureRetryService.retryFailedCompensations();
			if (result.recovered() > 0) {
				log.info("묶여 있던 재고를 되돌렸습니다. recovered={}, scanned={}",
						result.recovered(), result.scanned());
			}
		} catch (Exception exception) {
			// 예외를 전파하면 다음 주기가 돌지 않는다
			log.error("보상 실패 재처리 실행에 실패했습니다.", exception);
		}
	}
}
