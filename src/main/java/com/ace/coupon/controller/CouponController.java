package com.ace.coupon.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ace.common.ApiResponse;
import com.ace.coupon.dto.request.CouponCreateRequest;
import com.ace.coupon.dto.response.CouponCreateResponse;
import com.ace.coupon.service.CouponCreationService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/coupons")
@RequiredArgsConstructor
public class CouponController {

	private final CouponCreationService couponCreationService;

	@PostMapping
	public ResponseEntity<ApiResponse<CouponCreateResponse>> create(
			@Valid @RequestBody CouponCreateRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.success(couponCreationService.create(request)));
	}
}
