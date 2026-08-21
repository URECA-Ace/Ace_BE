package com.ace.coupon.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ace.common.ApiResponse;
import com.ace.coupon.dto.request.CouponEventCreateRequest;
import com.ace.coupon.dto.response.CouponEventCreateResponse;
import com.ace.coupon.service.CouponEventCreationService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/coupons")
@RequiredArgsConstructor
@Validated
public class CouponEventController {

	private final CouponEventCreationService couponEventCreationService;

	@PostMapping("/{couponId}/events")
	public ResponseEntity<ApiResponse<CouponEventCreateResponse>> create(
			@PathVariable(name = "couponId")
			@Positive(message = "couponId는 0보다 커야 합니다.")
			Long couponId,
			@Valid @RequestBody CouponEventCreateRequest request) {
		CouponEventCreateResponse response = couponEventCreationService.create(couponId, request);
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.success(response));
	}
}
