package com.ace.coupon.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ace.common.ApiResponse;
import com.ace.coupon.dto.request.IssueFailureActionRequest;
import com.ace.coupon.dto.response.IssueFailureActionResponse;
import com.ace.coupon.dto.response.IssueFailureActionResultResponse;
import com.ace.coupon.dto.response.IssueFailureDetailResponse;
import com.ace.coupon.dto.response.IssueFailurePageResponse;
import com.ace.coupon.dto.response.IssueFailureSummaryResponse;
import com.ace.coupon.enums.IssueFailureAction;
import com.ace.coupon.persistence.failure.IssueFailureStage;
import com.ace.coupon.persistence.failure.IssueFailureStatus;
import com.ace.coupon.service.IssueFailureAdminService;
import com.ace.coupon.service.IssueFailureQueryService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

// 운영자가 발급 실패(DLQ)를 조회하고 직접 조치하는 API
@RestController
@RequestMapping("/api/v1/issue-failures")
@RequiredArgsConstructor
@Validated
public class IssueFailureController {

	private final IssueFailureQueryService queryService;
	private final IssueFailureAdminService adminService;

	@GetMapping
	public ResponseEntity<ApiResponse<IssueFailurePageResponse>> findFailures(
			@RequestParam(name = "eventId", required = false) Long eventId,
			@RequestParam(name = "stage", required = false) IssueFailureStage stage,
			@RequestParam(name = "status", required = false) IssueFailureStatus status,
			@RequestParam(name = "requestId", required = false) String requestId,
			@RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
			@RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {

		return ResponseEntity.ok(ApiResponse.success(
				queryService.findFailures(eventId, stage, status, requestId, page, size)));
	}

	@GetMapping("/summary")
	public ResponseEntity<ApiResponse<IssueFailureSummaryResponse>> findSummary() {
		return ResponseEntity.ok(ApiResponse.success(queryService.findSummary()));
	}

	@GetMapping("/{failureId}")
	public ResponseEntity<ApiResponse<IssueFailureDetailResponse>> findDetail(
			@PathVariable long failureId) {
		return ResponseEntity.ok(ApiResponse.success(queryService.findDetail(failureId)));
	}

	@GetMapping("/{failureId}/actions")
	public ResponseEntity<ApiResponse<List<IssueFailureActionResponse>>> findActions(
			@PathVariable long failureId) {
		return ResponseEntity.ok(ApiResponse.success(queryService.findActions(failureId)));
	}

	@PostMapping("/{failureId}/actions/{action}")
	public ResponseEntity<ApiResponse<IssueFailureActionResultResponse>> execute(
			@PathVariable long failureId,
			@PathVariable IssueFailureAction action,
			@RequestBody(required = false) @Valid IssueFailureActionRequest request) {

		return ResponseEntity.ok(ApiResponse.success(
				adminService.execute(failureId, action, request)));
	}
}
