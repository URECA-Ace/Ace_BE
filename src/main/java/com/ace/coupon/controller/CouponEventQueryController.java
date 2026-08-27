package com.ace.coupon.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ace.common.ApiResponse;
import com.ace.coupon.dto.response.CouponEventSummaryResponse;
import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.service.CouponEventQueryService;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping({"/api/v1/coupons/events", "/api/v1/events"})
@RequiredArgsConstructor
@Validated
public class CouponEventQueryController {

	private final CouponEventQueryService couponEventQueryService;

	@GetMapping("/recent")
	public ResponseEntity<ApiResponse<List<CouponEventSummaryResponse>>> findRecentEvents(
			@RequestParam(required = false) CouponEventStatus status,
			@RequestParam(defaultValue = "6")
			@Min(value = 1, message = "size는 1 이상이어야 합니다.")
			@Max(value = 50, message = "size는 50 이하여야 합니다.")
			int size) {
		return ResponseEntity.ok(ApiResponse.success(
				couponEventQueryService.findRecentEvents(status, size)));
	}
}
