package com.ace.coupon.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import com.ace.common.ApiResponse;
import com.ace.coupon.dto.request.CouponCreateRequest;
import com.ace.coupon.dto.response.CouponSummaryResponse;
import com.ace.coupon.service.CouponCreationService;
import com.ace.coupon.service.CouponQueryService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/coupons")
@RequiredArgsConstructor
@Validated
public class CouponController {

	private final CouponCreationService couponCreationService;
	private final CouponQueryService couponQueryService;

	@GetMapping
	public ResponseEntity<ApiResponse<List<CouponSummaryResponse>>> findCoupons(
			@RequestParam(required = false) String keyword,
			@RequestParam(defaultValue = "6")
			@Min(value = 1, message = "size는 1 이상이어야 합니다.")
			@Max(value = 50, message = "size는 50 이하여야 합니다.")
			int size) {
		return ResponseEntity.ok(ApiResponse.success(couponQueryService.findCoupons(keyword, size)));
	}

	@PostMapping
	public ResponseEntity<ApiResponse<CouponSummaryResponse>> create(
			@Valid @RequestBody CouponCreateRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.success(couponCreationService.create(request)));
	}
}
