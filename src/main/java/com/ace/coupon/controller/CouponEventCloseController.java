package com.ace.coupon.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ace.common.ApiResponse;
import com.ace.coupon.dto.response.CouponEventCloseResponse;
import com.ace.coupon.service.CouponEventCloseService;

import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
@Validated
public class CouponEventCloseController {

	private final CouponEventCloseService couponEventCloseService;

	@PatchMapping("/{eventId}/close")
	public ResponseEntity<ApiResponse<CouponEventCloseResponse>> close(
			@PathVariable(name = "eventId")
			@Positive(message = "eventId는 0보다 커야 합니다.")
			Long eventId) {
		return ResponseEntity.ok(ApiResponse.success(couponEventCloseService.close(eventId)));
	}
}
