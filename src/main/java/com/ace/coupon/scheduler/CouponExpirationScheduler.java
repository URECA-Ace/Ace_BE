package com.ace.coupon.scheduler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ace.coupon.service.CouponExpirationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
		prefix = "coupon.expiration.scheduler",
		name = "enabled",
		havingValue = "true",
		matchIfMissing = true)
public class CouponExpirationScheduler {

	private final CouponExpirationService couponExpirationService;

	@Value("${coupon.expiration.scheduler.chunk-size:500}")
	private int chunkSize;

	@Scheduled(
			initialDelayString = "${coupon.expiration.scheduler.initial-delay-ms:10000}",
			fixedDelayString = "${coupon.expiration.scheduler.fixed-delay-ms:60000}")
	public void runExpiration() {
		long start = System.currentTimeMillis();
		try {
			int expiredCount = couponExpirationService.expireDueCoupons(chunkSize);
			long elapsedMs = System.currentTimeMillis() - start;
			if (expiredCount > 0) {
				log.info("[EXPIRATION] 유효기간이 만료된 쿠폰을 일괄 정리했습니다. expiredCount={}, elapsedMs={}ms",
						expiredCount, elapsedMs);
			}
		} catch (Exception e) {
			log.error("[EXPIRATION] 쿠폰 만료 스케줄러 실행 중 오류 발생", e);
		}
	}
}
