package com.ace.coupon.dto.response;

import java.time.OffsetDateTime;

import com.ace.coupon.enums.CouponEventStatus;

public record CouponEventStatsResponse(
		Long eventId,
		Long totalStock,
		Long allocatedQuantity,
		Long remainingStock,
		CouponEventStatus status,
		OffsetDateTime observedAt) {
}
