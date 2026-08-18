package com.ace.consistency.rowlevel.dto;

import java.time.LocalDateTime;

public record CouponRow(
		Long couponId,
		String couponName,
		String type,
		Long value,
		Integer validHours,
		LocalDateTime createdAt
) {
}
