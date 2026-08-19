package com.ace.coupon.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ace.common.ApiResponse;
import com.ace.coupon.dto.response.CouponEventStatsResponse;
import com.ace.coupon.service.CouponEventStatsService;

import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
@Validated
public class CouponEventStatsController {

	private final CouponEventStatsService couponEventStatsService;

	@GetMapping("/{eventId}/issuance-stats")
	public ResponseEntity<ApiResponse<CouponEventStatsResponse>> findStats(
			@PathVariable(name = "eventId")
			@Positive(message = "eventId는 0보다 커야 합니다.")
			Long eventId) {
		CouponEventStatsResponse result = couponEventStatsService.findStats(eventId);
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(ApiResponse.success(result));
	}
}
