package com.ace.consistency.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ace.common.ApiResponse;
import com.ace.consistency.common.VerificationResult;
import com.ace.consistency.dto.response.ConsistencyResultPageResponse;
import com.ace.consistency.dto.response.ConsistencyResultResponse;
import com.ace.consistency.dto.response.ConsistencyViolationPageResponse;
import com.ace.consistency.service.ConsistencyReportService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/consistency")
@RequiredArgsConstructor
@Validated
public class ConsistencyReportController {

	private final ConsistencyReportService service;

	@GetMapping("/results")
	public ResponseEntity<ApiResponse<ConsistencyResultPageResponse>> findResults(
			@RequestParam(name = "status", required = false) VerificationResult.Status status,
			@RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
			@RequestParam(name = "size", defaultValue = "8") @Min(1) @Max(100) int size) {
		return ResponseEntity.ok(ApiResponse.success(service.findResults(status, page, size)));
	}

	@GetMapping("/results/{resultId}")
	public ResponseEntity<ApiResponse<ConsistencyResultResponse>> findResult(
			@PathVariable(name = "resultId") long resultId) {
		return ResponseEntity.ok(ApiResponse.success(service.findResult(resultId)));
	}

	@GetMapping("/results/{resultId}/violations")
	public ResponseEntity<ApiResponse<ConsistencyViolationPageResponse>> findViolations(
			@PathVariable(name = "resultId") long resultId,
			@RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
			@RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
		return ResponseEntity.ok(ApiResponse.success(service.findViolations(resultId, page, size)));
	}

}
