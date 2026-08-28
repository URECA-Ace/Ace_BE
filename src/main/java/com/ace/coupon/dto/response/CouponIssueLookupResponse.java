package com.ace.coupon.dto.response;

public record CouponIssueLookupResponse(
		Long issueId,
		Long eventId,
		Long userId
) {
}
