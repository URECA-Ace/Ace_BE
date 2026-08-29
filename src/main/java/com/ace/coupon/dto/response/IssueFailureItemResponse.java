package com.ace.coupon.dto.response;

import java.time.LocalDateTime;

import com.ace.coupon.entity.IssueFailureLog;
import com.ace.coupon.persistence.failure.IssueFailureStage;
import com.ace.coupon.persistence.failure.IssueFailureStageGroup;
import com.ace.coupon.persistence.failure.IssueFailureStatus;

public record IssueFailureItemResponse(
		Long failureId,
		Long eventId,
		Long userId,
		String requestId,
		Long issueSequence,
		IssueFailureStage stage,
		IssueFailureStageGroup stageGroup,
		String stageGroupLabel,
		IssueFailureStatus status,
		String statusLabel,
		String compensationResult,
		int attemptCount,
		LocalDateTime lastAttemptAt,
		LocalDateTime occurredAt,
		LocalDateTime resolvedAt) {

	public static IssueFailureItemResponse from(IssueFailureLog failure) {
		IssueFailureStageGroup group = IssueFailureStageGroup.of(failure.getFailureStage());
		IssueFailureStatus status = IssueFailureStatus.of(
				failure.getFailureStage(), failure.getCompensationResult(), failure.isResolved());

		return new IssueFailureItemResponse(
				failure.getId(),
				failure.getEventId(),
				failure.getUserId(),
				failure.getRequestId(),
				failure.getIssueSequence(),
				failure.getFailureStage(),
				group,
				group.getLabel(),
				status,
				status.getLabel(),
				failure.getCompensationResult(),
				failure.getAttemptCount(),
				failure.getLastAttemptAt(),
				failure.getOccurredAt(),
				failure.getResolvedAt());
	}
}
