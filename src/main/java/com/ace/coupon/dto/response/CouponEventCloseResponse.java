package com.ace.coupon.dto.response;

import java.time.OffsetDateTime;

import com.ace.coupon.enums.CouponEventStatus;

public record CouponEventCloseResponse(
		Long eventId,
		CouponEventStatus status,
		OffsetDateTime closedAt) {
}
