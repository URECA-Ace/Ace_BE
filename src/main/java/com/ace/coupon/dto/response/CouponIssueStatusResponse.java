package com.ace.coupon.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.ace.coupon.enums.IssueRequestStatus;

public record CouponIssueStatusResponse(
		UUID requestId,
		Long eventId,
		Long userId,
		Long issueSequence,
		Long remainingStock,
		IssueRequestStatus status,
		OffsetDateTime decidedAt,
		String maskedUserName,
		String maskedUserEmail,
		String maskedUserPhone
)  
{}
