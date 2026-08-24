package com.ace.coupon.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import com.ace.common.ApiResponse;
import com.ace.coupon.dto.request.CouponCreateRequest;
import com.ace.coupon.dto.response.CouponCreateResponse;
import com.ace.coupon.dto.response.CouponSummaryResponse;
import com.ace.coupon.service.CouponCreationService;
import com.ace.coupon.service.CouponQueryService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/coupons")
@RequiredArgsConstructor
public class CouponController {

	private final CouponCreationService couponCreationService;
	private final CouponQueryService couponQueryService;

	@GetMapping
	public ResponseEntity<ApiResponse<List<CouponSummaryResponse>>> findCoupons(
			@RequestParam(required = false) String keyword) {
		return ResponseEntity.ok(ApiResponse.success(couponQueryService.findCoupons(keyword)));
	}

	@PostMapping
	public ResponseEntity<ApiResponse<CouponCreateResponse>> create(
			@Valid @RequestBody CouponCreateRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.success(couponCreationService.create(request)));
	}
}
