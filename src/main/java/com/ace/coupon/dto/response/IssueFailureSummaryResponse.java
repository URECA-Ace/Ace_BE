package com.ace.coupon.dto.response;

import java.util.List;

import com.ace.coupon.persistence.failure.IssueFailureStageGroup;

// 그룹마다 판정 기준이 달라 합산하지 않고 그룹별로 내려준다.
public record IssueFailureSummaryResponse(
		List<GroupSummary> groups,
		List<Long> blockedEventIds) {

	public record GroupSummary(
			IssueFailureStageGroup group,
			String label,
			long settled,
			long retryable,
			long unrecoverable) {
	}
}
