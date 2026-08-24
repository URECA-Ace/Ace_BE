package com.ace.coupon.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ace.common.ApiResponse;
import com.ace.coupon.dto.response.CouponEventSummaryResponse;
import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.service.CouponEventQueryService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class CouponEventQueryController {

	private final CouponEventQueryService couponEventQueryService;

	@GetMapping("/recent")
	public ResponseEntity<ApiResponse<List<CouponEventSummaryResponse>>> findRecentEvents(
			@RequestParam(required = false) CouponEventStatus status) {
		return ResponseEntity.ok(ApiResponse.success(couponEventQueryService.findRecentEvents(status)));
	}
}
