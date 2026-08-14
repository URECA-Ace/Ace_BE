package com.ace.coupon.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CouponIssueRequest(

        @NotNull(message = "userId는 필수입니다.")
        @Positive(message = "userId는 0보다 커야 합니다.")
        Long userId
) {
}