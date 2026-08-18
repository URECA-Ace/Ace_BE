package com.ace.consistency.rowlevel.dto;

import java.time.LocalDateTime;

public record CouponHistoryRow(
		Long historyId,
		Long issueId,
		String fromStatus,
		String toStatus,
		String eventUid,
		String actor,
		LocalDateTime occurredAt,
		LocalDateTime recordedAt
) {
}
