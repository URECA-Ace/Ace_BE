package com.ace.coupon.dto.response;

import java.util.List;

public record CouponIssuanceLogResponse(
		Long eventId,
		List<CouponIssuanceLogItemResponse> logs,
		Long nextSequence,
		boolean hasMore) {
}
