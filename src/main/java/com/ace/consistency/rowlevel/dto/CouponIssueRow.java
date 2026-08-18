package com.ace.consistency.rowlevel.dto;

import java.time.LocalDateTime;

public record CouponIssueRow(
		Long issueId,
		Long eventId,
		Long userId,
		Integer issueSequence,
		String requestId,
		String messageId,
		String status,
		LocalDateTime issuedAt,
		LocalDateTime validFrom,
		LocalDateTime validTo,
		LocalDateTime usedAt,
		LocalDateTime canceledAt,
		LocalDateTime createdAt
) {
}
