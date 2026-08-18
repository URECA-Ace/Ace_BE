package com.ace.coupon.service;

import com.ace.coupon.dto.response.CouponIssueAcceptedResponse;
import com.ace.coupon.dto.response.CouponIssueStatusResponse;

import java.util.UUID;

public interface CouponIssueService {

    CouponIssueAcceptedResponse issue(
            Long eventId,
            Long userId,
            UUID idempotencyKey
    );

	CouponIssueStatusResponse findStatus(Long eventId, UUID requestId);
}
