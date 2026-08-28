package com.ace.coupon.dto.response;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.enums.CouponEventStatus;

public record CouponEventSummaryResponse(
		Long eventId,
		Long couponId,
		String couponName,
		Integer round,
		Integer totalStock,
		Integer remainingStock,
		CouponEventStatus status,
		OffsetDateTime openAt,
		OffsetDateTime closeAt,
		OffsetDateTime statusChangedAt) {

	public static CouponEventSummaryResponse from(CouponEvent event, ZoneId zoneId) {
		return new CouponEventSummaryResponse(
				event.getId(),
				event.getCoupon().getId(),
				event.getCoupon().getCouponName(),
				event.getRound(),
				event.getTotalStock(),
				event.getRemainingStock(),
				event.getStatus(),
				event.getOpenAt().atZone(zoneId).toOffsetDateTime(),
				event.getCloseAt().atZone(zoneId).toOffsetDateTime(),
				event.getUpdatedAt().atZone(zoneId).toOffsetDateTime());
	}
}
