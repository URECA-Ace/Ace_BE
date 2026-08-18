package com.ace.consistency.rowlevel.dto;

import java.time.LocalDateTime;

public record CouponEventRow(
		Long eventId,
		Long couponId,
		Integer round,
		LocalDateTime openAt,
		LocalDateTime closeAt,
		Integer totalStock,
		Integer remainingStock,
		Integer issuedQuantity,
		Integer perUserLimit,
		String status,
		LocalDateTime createdAt,
		LocalDateTime updatedAt
) {
}
