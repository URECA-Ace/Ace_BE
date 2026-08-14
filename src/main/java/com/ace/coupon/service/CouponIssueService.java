package com.ace.coupon.service;

import com.ace.coupon.dto.response.CouponIssueAcceptedResponse;

import java.util.UUID;

public interface CouponIssueService {

    CouponIssueAcceptedResponse issue(
            Long eventId,
            Long userId,
            UUID idempotencyKey
    );
}
