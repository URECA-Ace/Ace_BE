package com.ace.coupon.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ace.common.ApiResponse;
import com.ace.coupon.dto.response.CouponIssuanceLogResponse;
import com.ace.coupon.service.CouponIssuanceLogService;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
@Validated
public class CouponIssuanceLogController {

	private final CouponIssuanceLogService couponIssuanceLogService;

	@GetMapping("/{eventId}/issuance-logs")
	public ResponseEntity<ApiResponse<CouponIssuanceLogResponse>> findLogs(
			@PathVariable(name = "eventId")
			@Positive(message = "eventId는 0보다 커야 합니다.")
			Long eventId,
			@RequestParam(name = "afterSequence", defaultValue = "0")
			@Min(value = 0, message = "afterSequence는 0 이상이어야 합니다.")
			int afterSequence,
			@RequestParam(name = "size", defaultValue = "200")
			@Min(value = 1, message = "size는 1 이상이어야 합니다.")
			@Max(value = 500, message = "size는 500 이하여야 합니다.")
			int size) {
		CouponIssuanceLogResponse result = couponIssuanceLogService
				.findLogs(eventId, afterSequence, size);
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(ApiResponse.success(result));
	}
}
