package com.ace.coupon.dto.response;

import java.time.LocalDateTime;

import com.ace.coupon.entity.Coupon;

public record CouponSummaryResponse(
		Long couponId,
		String couponName,
		String type,
		Long value,
		Integer validHours,
		LocalDateTime createdAt) {

	public static CouponSummaryResponse from(Coupon coupon) {
		return new CouponSummaryResponse(
				coupon.getId(),
				coupon.getCouponName(),
				coupon.getType(),
				coupon.getValue(),
				coupon.getValidHours(),
				coupon.getCreatedAt());
	}
}
