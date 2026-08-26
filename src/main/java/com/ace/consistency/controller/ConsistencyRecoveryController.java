package com.ace.consistency.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ace.common.ApiResponse;
import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.consistency.dto.RecoveryRequest;
import com.ace.consistency.dto.RecoveryResultResponse;
import com.ace.consistency.recovery.ConsistencyRecoveryDispatcher;
import com.ace.consistency.recovery.RecoveryAction;
import com.ace.consistency.recovery.RecoveryResult;

import lombok.RequiredArgsConstructor;

/** 운영자가 FAIL 상태인 정합성 검증 결과를 보고 복구를 실행시키는 내부 API. */
@RestController
@RequestMapping("/internal/consistency/results")
@RequiredArgsConstructor
public class ConsistencyRecoveryController {

	private final ConsistencyRecoveryDispatcher dispatcher;

	@PostMapping("/{resultId}/recover")
	public ResponseEntity<ApiResponse<RecoveryResultResponse>> recover(
			@PathVariable Long resultId,
			@RequestBody RecoveryRequest request) {

		RecoveryAction action = parseAction(request.getAction());
		RecoveryResult result = dispatcher.recover(resultId, action);

		return ResponseEntity.ok(ApiResponse.success(RecoveryResultResponse.from(result)));
	}

	private RecoveryAction parseAction(String action) {
		try {
			return RecoveryAction.valueOf(action);
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new CouponException(ErrorCode.INVALID_PARAMETER, "지원하지 않는 복구 액션입니다: " + action);
		}
	}
}
