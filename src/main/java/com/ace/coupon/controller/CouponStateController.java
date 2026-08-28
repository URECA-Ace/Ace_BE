package com.ace.coupon.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ace.common.ApiResponse;
import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.request.CouponStateChangeRequest;
import com.ace.coupon.dto.response.CouponIssueLookupResponse;
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

	@GetMapping("/issues/lookup")
	public ResponseEntity<ApiResponse<CouponIssueLookupResponse>> findIssue(
			@RequestParam(name = "eventId")
			@Positive(message = "eventId는 0보다 커야 합니다.")
			Long eventId,

			@RequestParam(name = "userId")
			@Positive(message = "userId는 0보다 커야 합니다.")
			Long userId) {

		CouponIssueLookupResponse response = couponStateService.findIssue(eventId, userId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

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

	@PatchMapping("/{issueId}/expire")
	public ResponseEntity<ApiResponse<CouponStateChangeResponse>> expire(
			@PathVariable(name = "issueId")
			@Positive(message = "issueId는 0보다 커야 합니다.")
			Long issueId,

			@RequestHeader(name = "Idempotency-Key")
			String idempotencyKey,

			@Valid
			@RequestBody
			CouponStateChangeRequest request) {

		UUID parsedIdempotencyKey = parseIdempotencyKey(idempotencyKey);

		CouponStateChangeResponse response = couponStateService.expire(
				issueId,
				request.userId(),
				parsedIdempotencyKey,
				request.reason());

		return ResponseEntity.ok(ApiResponse.success(response));
	}
  
	private UUID parseIdempotencyKey(String value) {
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException exception) {
			throw new CouponException(ErrorCode.INVALID_IDEMPOTENCY_KEY);
		}
	}
}
