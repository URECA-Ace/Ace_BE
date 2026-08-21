package com.ace.coupon.dto.response;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.enums.CouponEventStatus;

public record CouponEventCreateResponse(
		Long eventId,
		Long couponId,
		Integer round,
		Integer totalStock,
		Integer remainingStock,
		Integer perUserLimit,
		CouponEventStatus status,
		OffsetDateTime openAt,
		OffsetDateTime closeAt) {

	public static CouponEventCreateResponse from(CouponEvent event, Long couponId, ZoneId zoneId) {
		return new CouponEventCreateResponse(
				event.getId(),
				couponId,
				event.getRound(),
				event.getTotalStock(),
				event.getRemainingStock(),
				event.getPerUserLimit(),
				event.getStatus(),
				event.getOpenAt().atZone(zoneId).toOffsetDateTime(),
				event.getCloseAt().atZone(zoneId).toOffsetDateTime());
	}
}
