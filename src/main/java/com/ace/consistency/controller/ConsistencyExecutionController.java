package com.ace.consistency.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.ace.common.ApiResponse;
import com.ace.consistency.common.Scope;
import com.ace.consistency.dto.request.ConsistencyVerificationRequest;
import com.ace.consistency.dto.response.ConsistencyCheckCatalogResponse;
import com.ace.consistency.dto.response.ConsistencyVerificationResponse;
import com.ace.consistency.dto.response.ConsistencyJobExecutionResponse;
import com.ace.consistency.service.ConsistencyVerificationService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/consistency")
@RequiredArgsConstructor
@Validated
public class ConsistencyExecutionController {

	private final ConsistencyVerificationService service;

	@GetMapping("/checks")
	public ResponseEntity<ApiResponse<ConsistencyCheckCatalogResponse>> findSupportedChecks(
			@RequestParam(name = "scopeType") @NotNull Scope.ScopeType scopeType) {
		return ResponseEntity.ok(ApiResponse.success(service.findSupportedChecks(scopeType)));
	}

	@PostMapping("/verifications")
	public ResponseEntity<ApiResponse<ConsistencyVerificationResponse>> verify(
			@Valid @RequestBody ConsistencyVerificationRequest request) {
		ConsistencyVerificationResponse response = service.verify(request);
		HttpStatus status = response.executionType() == ConsistencyVerificationResponse.ExecutionType.ASYNC
				? HttpStatus.ACCEPTED
				: HttpStatus.OK;
		return ResponseEntity.status(status).body(ApiResponse.success(response));
	}

	@GetMapping("/verifications/{jobExecutionId}")
	public ResponseEntity<ApiResponse<ConsistencyJobExecutionResponse>> findExecution(
			@PathVariable(name = "jobExecutionId") long jobExecutionId) {
		return ResponseEntity.ok(ApiResponse.success(service.findExecution(jobExecutionId)));
	}

	@PostMapping("/verifications/{jobExecutionId}/stop")
	public ResponseEntity<ApiResponse<Void>> stop(
			@PathVariable(name = "jobExecutionId") long jobExecutionId) {
		service.stop(jobExecutionId);
		return ResponseEntity.ok(ApiResponse.success());
	}

	@PostMapping("/verifications/results/{resultId}/restart")
	public ResponseEntity<ApiResponse<ConsistencyJobExecutionResponse>> restart(
			@PathVariable(name = "resultId") long resultId) {
		return ResponseEntity.accepted().body(ApiResponse.success(
				ConsistencyJobExecutionResponse.from(service.restartInterruptedResult(resultId))));
	}
}
