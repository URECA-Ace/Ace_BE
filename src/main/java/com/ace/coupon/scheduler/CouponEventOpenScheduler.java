package com.ace.coupon.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ace.coupon.service.CouponEventOpenService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
		prefix = "coupon.campaign.status-scheduler",
		name = "enabled",
		havingValue = "true",
		matchIfMissing = true)
public class CouponEventOpenScheduler {

	private final CouponEventOpenService couponEventOpenService;

	@Scheduled(
			cron = "${coupon.campaign.status-scheduler.cron:1,31 * * * * *}",
			zone = "${coupon.campaign.status-scheduler.zone:Asia/Seoul}")
	public void transitionDueEvents() {
		var result = couponEventOpenService.transitionDueEvents();
		if (result.openedCount() > 0 || result.closedCount() > 0) {
			log.info(
					"예약 시각에 도달한 쿠폰 캠페인 상태를 전환했습니다. openedCount={}, closedCount={}",
					result.openedCount(),
					result.closedCount());
		}
	}
}
