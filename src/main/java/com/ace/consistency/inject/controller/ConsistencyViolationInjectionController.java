package com.ace.consistency.inject.controller;

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ace.common.ApiResponse;
import com.ace.consistency.inject.ConsistencyViolationInjectionDispatcher;
import com.ace.consistency.inject.InjectionResult;
import com.ace.consistency.inject.dto.ConsistencyViolationInjectionRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 관리 화면에서 정합성 위반 데이터를 실제로 심어, 검증 -> 복구 과정을 눈으로 확인할 수 있게 하는
 * 시연/운영 확인용 내부 API. 운영 DB를 직접 오염시키므로 기본적으로 비활성화되어 있고,
 * consistency.injection.admin.enabled=true로 명시적으로 켜야 노출된다.
 */
@RestController
@RequestMapping({"/internal/consistency", "/api/v1/consistency"})
@ConditionalOnProperty(prefix = "consistency.injection.admin", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Validated
public class ConsistencyViolationInjectionController {

	private final ConsistencyViolationInjectionDispatcher dispatcher;

	/** 위반을 주입할 수 있는 체크 목록과 각 위반 내용 설명을 조회한다. */
	@GetMapping("/injectors")
	public ResponseEntity<ApiResponse<Map<String, String>>> availableInjectors() {
		return ResponseEntity.ok(ApiResponse.success(dispatcher.availableInjectors()));
	}

	/** 선택한 체크의 위반 데이터를 지정한 이벤트에 실제로 심는다. */
	@PostMapping("/injections")
	public ResponseEntity<ApiResponse<InjectionResult>> inject(
			@Valid @RequestBody ConsistencyViolationInjectionRequest request) {
		InjectionResult result = dispatcher.inject(request.checkName(), request.eventId());
		return ResponseEntity.ok(ApiResponse.success(result));
	}
}
