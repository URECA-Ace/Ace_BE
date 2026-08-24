package com.ace.coupon.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ace.common.ApiResponse;
import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.request.CouponStateChangeRequest;
import com.ace.coupon.dto.response.CouponStateChangeResponse;
import com.ace.coupon.service.CouponStateService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/coupons")
@RequiredArgsConstructor
@Validated 
public class CouponStateController {

	private final CouponStateService couponStateService;

	@PatchMapping("/{issueId}/use")
	public ResponseEntity<ApiResponse<CouponStateChangeResponse>> use(
			@PathVariable(name = "issueId")
			@Positive(message = "issueId는 0보다 커야 합니다.")
			Long issueId,

			@RequestHeader(name = "Idempotency-Key")
			String idempotencyKey,

			@Valid
			@RequestBody 
			CouponStateChangeRequest request) {

		UUID parsedIdempotencyKey = parseIdempotencyKey(idempotencyKey);
		
		CouponStateChangeResponse response = couponStateService.use(
				issueId,
				request.userId(),
				parsedIdempotencyKey,
				request.reason());
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	
	
	@PatchMapping("/{issueId}/cancel")
	public ResponseEntity<ApiResponse<CouponStateChangeResponse>> cancel(
			@PathVariable(name = "issueId")
			@Positive(message = "issueId는 0보다 커야 합니다.")
			Long issueId,  

			@RequestHeader(name = "Idempotency-Key")
			String idempotencyKey,
 
			@Valid
			@RequestBody
			CouponStateChangeRequest request) {
 
		UUID parsedIdempotencyKey = parseIdempotencyKey(idempotencyKey);
		
		CouponStateChangeResponse response = couponStateService.cancel(
				issueId,
				request.userId(),
				parsedIdempotencyKey,
				request.reason());
		
		return ResponseEntity.ok(ApiResponse.success(response));
	}
  
	private UUID parseIdempotencyKey(String value) { 
		
		if (value == null || value.isBlank()) {
			throw new CouponException(ErrorCode.MISSING_IDEMPOTENCY_KEY);  
			
		}
		try {
			return UUID.fromString(value);
			 
		} catch (IllegalArgumentException exception) {
			  
			throw new CouponException(ErrorCode.INVALID_IDEMPOTENCY_KEY);   
		} 
	}
}
