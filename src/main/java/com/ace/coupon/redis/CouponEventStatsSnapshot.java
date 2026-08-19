package com.ace.coupon.redis;

import java.time.Instant;

import com.ace.coupon.enums.CouponEventStatus;

public record CouponEventStatsSnapshot(
		Long campaignId,
		Long totalStock,
		Long allocatedQuantity,
		Long remainingStock,
		CouponEventStatus status,
		Instant observedAt) {
}
