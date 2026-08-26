package com.ace.coupon.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.ace.coupon.enums.CouponIssueStatus;

public record CouponStateChangeResponse(
		UUID requestId,
		Long issueId,
		Long eventId,
		Long userId,
		CouponIssueStatus previousStatus,
		CouponIssueStatus currentStatus,
		LocalDateTime changedAt)   
{}
