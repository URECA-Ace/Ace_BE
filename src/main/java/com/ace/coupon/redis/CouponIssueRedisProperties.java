package com.ace.coupon.redis;

import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "coupon.issue.redis")
public record CouponIssueRedisProperties(
		Duration retention,
		ZoneId zoneId) {

	private static final Duration DEFAULT_RETENTION = Duration.ofDays(7);
	private static final ZoneId DEFAULT_ZONE_ID = ZoneId.of("Asia/Seoul");

	public CouponIssueRedisProperties {
		retention = retention == null ? DEFAULT_RETENTION : retention;
		zoneId = zoneId == null ? DEFAULT_ZONE_ID : zoneId;

		if (retention.isNegative() || retention.isZero()) {
			throw new IllegalArgumentException("Redis retention은 양수여야 합니다.");
		}
	}
}
