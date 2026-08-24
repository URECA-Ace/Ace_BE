package com.ace.consistency.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ace.common.ApiResponse;
import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.ConsistencyVerificationRunner;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResult;

import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/** 운영자가 특정 이벤트의 정합성 Check 전체를 수동 실행하는 내부 API. */
@RestController
@RequestMapping("/internal/consistency/events")
@RequiredArgsConstructor
@Validated
public class ConsistencyVerificationController {

	private final ConsistencyVerificationRunner runner;
	private final List<ConsistencyCheck> checks;

	@PostMapping("/{eventId}/verify")
	public ResponseEntity<ApiResponse<List<VerificationResult>>> verifyEvent(
			@PathVariable(name = "eventId")
			@Positive(message = "eventId는 0보다 커야 합니다.")
			Long eventId) {
		List<ConsistencyCheck> eventChecks = checks.stream()
				.filter(check -> check.supportedScopeTypes().contains(Scope.ScopeType.EVENT))
				.toList();

		List<VerificationResult> results = runner.run(
				eventChecks,
				Scope.ofEvent(eventId),
				TriggerType.ON_DEMAND);

		return ResponseEntity.ok(ApiResponse.success(results));
	}
}
