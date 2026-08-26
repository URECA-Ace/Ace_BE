package com.ace.coupon.dto.response;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import com.ace.coupon.entity.Coupon;

public record CouponSummaryResponse(
		Long couponId,
		String couponName,
		String type,
		Long value,
		Integer validHours,
		OffsetDateTime createdAt) {

	public static CouponSummaryResponse from(Coupon coupon, ZoneId zoneId) {
		return new CouponSummaryResponse(
				coupon.getId(),
				coupon.getCouponName(),
				coupon.getType(),
				coupon.getValue(),
				coupon.getValidHours(),
				coupon.getCreatedAt().atZone(zoneId).toOffsetDateTime());
	}
}
