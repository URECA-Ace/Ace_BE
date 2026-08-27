package com.ace.consistency.controller;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.batch.core.job.JobExecution;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ace.common.ApiResponse;
import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.ConsistencyVerificationRunner;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;

import lombok.RequiredArgsConstructor;

/** 운영자가 전체 데이터 정합성 Check를 수동으로 시작하는 내부 API. */
@RestController
@RequestMapping("/internal/consistency")
@RequiredArgsConstructor
public class ConsistencyVerificationController {

	private final ConsistencyVerificationRunner runner;
	private final List<ConsistencyCheck> checks;

	@PostMapping("/verify")
	public ResponseEntity<ApiResponse<Long>> verifyAll() {
		List<ConsistencyCheck> allChecks = checks.stream()
				.filter(check -> check.supportedScopeTypes().contains(Scope.ScopeType.ALL))
				.toList();

		JobExecution execution = runner.runAsync(
				allChecks,
				Scope.all(LocalDateTime.now()),
				TriggerType.ON_DEMAND);

		return ResponseEntity.status(HttpStatus.ACCEPTED)
				.body(ApiResponse.success(execution.getId()));
	}
}
