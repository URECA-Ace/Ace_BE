package com.ace.coupon.dto.response;

import java.util.List;

import com.ace.coupon.entity.IssueFailureLog;

public record IssueFailureDetailResponse(
		IssueFailureItemResponse summary,
		String errorMessage,
		String incidentId,
		String resolvedBy,
		String resolveReason,
		String resolveProbeResult,
		List<IssueFailureActionResponse> availableActions) {

	public static IssueFailureDetailResponse of(
			IssueFailureLog failure, List<IssueFailureActionResponse> actions) {
		return new IssueFailureDetailResponse(
				IssueFailureItemResponse.from(failure),
				failure.getErrorMessage(),
				failure.getIncidentId(),
				failure.getResolvedBy(),
				failure.getResolveReason(),
				failure.getResolveProbeResult(),
				actions);
	}
}
