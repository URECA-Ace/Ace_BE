package com.ace.coupon.dto.response;

import java.util.List;

public record CouponIssuanceLogResponse(
		Long eventId,
		List<CouponIssuanceLogItemResponse> logs,
		Integer nextSequence,
		boolean hasMore) {
}
