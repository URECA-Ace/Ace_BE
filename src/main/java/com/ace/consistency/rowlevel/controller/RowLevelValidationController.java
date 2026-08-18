package com.ace.consistency.rowlevel.controller;

import com.ace.common.ApiResponse;
import com.ace.consistency.rowlevel.dto.CouponEventRow;
import com.ace.consistency.rowlevel.dto.BatchRowValidationRequest;
import com.ace.consistency.rowlevel.dto.BatchRowValidationResponse;
import com.ace.consistency.rowlevel.dto.CouponHistoryRow;
import com.ace.consistency.rowlevel.dto.CouponIssueRow;
import com.ace.consistency.rowlevel.dto.CouponRow;
import com.ace.consistency.rowlevel.dto.RowValidationRequest;
import com.ace.consistency.rowlevel.dto.RowValidationResponse;
import com.ace.consistency.rowlevel.service.RowLevelValidationService;
import com.ace.consistency.rowlevel.service.CouponIssueBatchValidationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/consistency/row-level")
public class RowLevelValidationController {

	private final RowLevelValidationService validationService;
	private final CouponIssueBatchValidationService batchValidationService;

	public RowLevelValidationController(RowLevelValidationService validationService,
			CouponIssueBatchValidationService batchValidationService) {
		this.validationService = validationService;
		this.batchValidationService = batchValidationService;
	}

	@PostMapping("/coupons/validate")
	public ApiResponse<RowValidationResponse> validateCoupon(@RequestBody RowValidationRequest<CouponRow> request) {
		validateRequest(request);
		return ApiResponse.success(validationService.validateCoupon(request.row(), request.snapshotAt()));
	}

	@PostMapping("/coupon-events/validate")
	public ApiResponse<RowValidationResponse> validateEvent(@RequestBody RowValidationRequest<CouponEventRow> request) {
		validateRequest(request);
		return ApiResponse.success(validationService.validateEvent(request.row(), request.snapshotAt()));
	}

	@PostMapping("/coupon-issues/validate")
	public ApiResponse<RowValidationResponse> validateIssue(@RequestBody RowValidationRequest<CouponIssueRow> request) {
		validateRequest(request);
		return ApiResponse.success(validationService.validateIssue(request.row(), request.snapshotAt()));
	}

	@PostMapping("/coupon-issues/validate-batch")
	public ApiResponse<BatchRowValidationResponse> validateIssueBatch(@RequestBody BatchRowValidationRequest request) {
		try {
			return ApiResponse.success(batchValidationService.validate(request));
		} catch (IllegalArgumentException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
		}
	}

	@PostMapping("/coupon-histories/validate")
	public ApiResponse<RowValidationResponse> validateHistory(@RequestBody RowValidationRequest<CouponHistoryRow> request) {
		validateRequest(request);
		return ApiResponse.success(validationService.validateHistory(request.row(), request.snapshotAt()));
	}

	private void validateRequest(RowValidationRequest<?> request) {
		if (request == null || request.row() == null || request.snapshotAt() == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"재현 가능한 검증을 위해 snapshotAt과 검증 대상 row는 필수입니다.");
		}
	}
}
