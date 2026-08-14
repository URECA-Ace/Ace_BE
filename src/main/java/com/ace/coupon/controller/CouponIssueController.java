package com.ace.coupon.controller;

import com.ace.common.ApiResponse;
import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.request.CouponIssueRequest;
import com.ace.coupon.dto.response.CouponIssueAcceptedResponse;
import com.ace.coupon.service.CouponIssueService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
@Validated
public class CouponIssueController {

    private final CouponIssueService couponIssueService;

    @PostMapping("/{eventId}/issues")
    public ResponseEntity<ApiResponse<CouponIssueAcceptedResponse>> issue(
            @PathVariable(name = "eventId")
            @Positive(message = "eventId는 0보다 커야 합니다.")
            Long eventId,

            @RequestHeader(name = "Idempotency-Key")
            String idempotencyKey,

            @Valid
            @RequestBody
            CouponIssueRequest request
    ) {
        UUID parsedIdempotencyKey =
                parseIdempotencyKey(idempotencyKey);

        CouponIssueAcceptedResponse result =
                couponIssueService.issue(
                        eventId,
                        request.userId(),
                        parsedIdempotencyKey
                );

        ApiResponse<CouponIssueAcceptedResponse> response =
                ApiResponse.success(result);

        return ResponseEntity
                .accepted()
                .body(response);
    }

    private UUID parseIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new CouponException(
                    ErrorCode.MISSING_IDEMPOTENCY_KEY
            );
        }

        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new CouponException(
                    ErrorCode.INVALID_IDEMPOTENCY_KEY
            );
        }
    }
}
