package com.ace.coupon.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ace.coupon.service.CouponEventAggregateSnapshotService;
import com.ace.coupon.service.CouponEventLifecycleService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 회차 집계 컬럼 갱신과 상태 전환을 주기적으로 실행
// 1. 발급 중인 회차의 집계 컬럼 갱신
// 2. 소진/마감 전환
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
		prefix = "coupon.campaign.aggregate-snapshot",
		name = "enabled",
		havingValue = "true")
public class CouponEventAggregateSnapshotScheduler {

	private final CouponEventAggregateSnapshotService snapshotService;
	private final CouponEventLifecycleService lifecycleService;

	@Scheduled(
			initialDelayString = "${coupon.campaign.aggregate-snapshot.initial-delay-ms:5000}",
			fixedDelayString = "${coupon.campaign.aggregate-snapshot.fixed-delay-ms:5000}")
	public void snapshotAndAdvanceEvents() {
		snapshotActiveEvents();
		advanceEventStatus();
	}

	private void snapshotActiveEvents() {
		try {
			CouponEventAggregateSnapshotService.SweepResult result =
					snapshotService.snapshotActiveEvents();
			if (result.applied() > 0) {
				log.info("쿠폰 회차 집계 컬럼을 갱신했습니다. applied={}", result.applied());
			}
		} catch (Exception exception) {
			log.error("쿠폰 회차 집계 스냅샷 실행에 실패했습니다.", exception);
		}
	}

	private void advanceEventStatus() {
		try {
			CouponEventLifecycleService.SweepResult result = lifecycleService.sweep();
			if (result.soldOut() > 0 || result.closed() > 0) {
				log.info("쿠폰 회차 상태를 전환했습니다. soldOut={}, closed={}",
						result.soldOut(), result.closed());
			}
		} catch (Exception exception) {
			log.error("쿠폰 회차 상태 전환 실행에 실패했습니다.", exception);
		}
	}
}
