package com.ace.coupon.dto.response;

import com.ace.coupon.enums.IssueRequestStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CouponIssueAcceptedResponse(
        UUID requestId,
        Long eventId,
        Long userId,
        Long issueSequence,
        Long remainingStock,
        IssueRequestStatus status,
        OffsetDateTime acceptedAt
) {

    public static CouponIssueAcceptedResponse accepted(
            UUID requestId,
            Long eventId,
            Long userId,
            Long issueSequence,
            Long remainingStock,
            OffsetDateTime acceptedAt
    ) {
        return new CouponIssueAcceptedResponse(
                requestId,
                eventId,
                userId,
                issueSequence,
                remainingStock,
                IssueRequestStatus.ACCEPTED,
                acceptedAt
        );
    }
}