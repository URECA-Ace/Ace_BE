package com.ace.consistency.recovery.controller;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.validation.annotation.Validated;

import com.ace.common.ApiResponse;
import com.ace.common.ErrorCode;
import com.ace.common.exception.ConsistencyCheckException;
import com.ace.consistency.recovery.dto.RecoveryActionResponse;
import com.ace.consistency.recovery.dto.RecoveryRequest;
import com.ace.consistency.recovery.dto.RecoveryResultResponse;
import com.ace.consistency.recovery.ConsistencyRecoveryDispatcher;
import com.ace.consistency.recovery.RecoveryResult;
import com.ace.consistency.recovery.enums.RecoveryAction;
import com.ace.consistency.recovery.dto.RecoveryHistoryPageResponse;
import com.ace.consistency.recovery.repository.RecoveryResultRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import lombok.RequiredArgsConstructor;

/** 운영자가 FAIL 상태인 정합성 검증 결과를 보고 복구를 실행시키는 내부 API. */
@RestController
@RequestMapping({"/internal/consistency", "/api/v1/consistency"})
@ConditionalOnProperty(prefix = "consistency.recovery.admin", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Validated
public class ConsistencyRecoveryController {

	private final ConsistencyRecoveryDispatcher dispatcher;
	private final RecoveryResultRepository recoveryResultRepository;

	/**
	 * 특정 정합성 검증 실패 결과에 적용할 수 있는 복구 방법을 조회한다.
	 * 관리 화면에서 복구 방법 선택지를 구성할 때 호출하며, 해당 검사에 등록된 복구 정책이
	 * 없으면 오류 대신 빈 목록을 반환한다.
	 */
	@GetMapping({"/results/{resultId}/recovery-methods", "/results/{resultId}/actions"})
	public ResponseEntity<ApiResponse<List<RecoveryActionResponse>>> availableActions(
			@PathVariable(name = "resultId") Long resultId) {
		List<RecoveryActionResponse> actions = dispatcher.availableActions(resultId).stream()
				.map(RecoveryActionResponse::from)
				.toList();
		return ResponseEntity.ok(ApiResponse.success(actions));
	}

	/**
	 * 선택한 복구 액션으로 정합성 실패 결과를 복구하고 즉시 재검증한다.
	 * 복구 정책이 나눈 실제 처리 단위별로 복구 이력을 저장하며, 저장된 결과 목록을 반환한다.
	 * FAIL 상태가 아니거나 해당 검사·스코프에서 지원하지 않는 복구 요청은 거부한다.
	 */
	@PostMapping({"/results/{resultId}/recoveries", "/results/{resultId}/recover"})
	public ResponseEntity<ApiResponse<List<RecoveryResultResponse>>> recover(
			@PathVariable(name = "resultId") Long resultId,
			@Valid @RequestBody RecoveryRequest request) {

		RecoveryAction action = parseAction(request.getAction());
		List<RecoveryResult> results = dispatcher.recover(resultId, action);

		List<RecoveryResultResponse> response = results.stream()
				.map(RecoveryResultResponse::from)
				.toList();

		return ResponseEntity.ok(ApiResponse.success(response));
	}

	/**
	 * 전체 정합성 복구 이력을 요청 시각 기준 최신순으로 조회한다.
	 * 관리 화면의 복구 이력 영역에서 사용하며, 페이지 번호와 페이지 크기로 조회 범위를 제한한다.
	 */
	@GetMapping("/recoveries")
	public ResponseEntity<ApiResponse<RecoveryHistoryPageResponse>> recoveryHistory(
			@RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
			@RequestParam(name = "size", defaultValue = "10") @Min(1) @Max(100) int size) {
		return ResponseEntity.ok(ApiResponse.success(RecoveryHistoryPageResponse.from(
				recoveryResultRepository.findAllByOrderByCreatedAtDesc(
						org.springframework.data.domain.PageRequest.of(page, size)))));
	}

	private RecoveryAction parseAction(String action) {
		try {
			return RecoveryAction.valueOf(action);
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new ConsistencyCheckException(ErrorCode.INVALID_PARAMETER, "지원하지 않는 복구 액션입니다: " + action);
		}
	}
}
