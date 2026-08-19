package com.ace.coupon.redis;

import java.time.Instant;
import java.util.UUID;

import com.ace.coupon.enums.IssueRequestStatus;

public record CouponIssueRequestState(
		UUID requestId,
		Long campaignId,
		Long userId,
		IssueRequestStatus status,
		Long issueSequence,
		Long remainingStock,
		Instant decidedAt) {
}
