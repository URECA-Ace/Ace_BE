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
		prefix = "coupon.campaign.open-scheduler",
		name = "enabled",
		havingValue = "true")
public class CouponEventOpenScheduler {

	private final CouponEventOpenService couponEventOpenService;

	@Scheduled(
			initialDelayString = "${coupon.campaign.open-scheduler.initial-delay-ms:1000}",
			fixedDelayString = "${coupon.campaign.open-scheduler.fixed-delay-ms:1000}")
	public void openDueEvents() {
		int openedCount = couponEventOpenService.openDueEvents();
		if (openedCount > 0) {
			log.info("예약 시각에 도달한 쿠폰 캠페인을 오픈했습니다. openedCount={}", openedCount);
		}
	}
}
