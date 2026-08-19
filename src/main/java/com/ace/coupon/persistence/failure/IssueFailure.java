package com.ace.coupon.persistence.failure;

import java.time.Instant;
import com.ace.coupon.persistence.IssueRecord;

// 실패 기록 입력
public record IssueFailure(
		long eventId,
		long userId,
		String requestId,
		Long issueSequence,
		IssueFailureStage stage,
		String compensationResult,
		String errorMessage,
		String incidentId,
		Instant occurredAt) {

	public IssueFailure {
		if (requestId == null || requestId.isBlank()) {
			throw new IllegalArgumentException("requestId가 필요합니다.");
		}
		if (stage == null) {
			throw new IllegalArgumentException("실패 단계가 필요합니다.");
		}
		if (occurredAt == null) {
			throw new IllegalArgumentException("발생 시각이 필요합니다.");
		}
	}

	public static IssueFailure of(
			IssueRecord record,
			IssueFailureStage stage,
			String compensationResult,
			String errorMessage,
			String incidentId) {
		return new IssueFailure(
				record.campaignId(),
				record.userId(),
				record.requestId().toString(),
				record.issueSequence(),
				stage,
				compensationResult,
				errorMessage,
				incidentId,
				Instant.now());
	}
}
