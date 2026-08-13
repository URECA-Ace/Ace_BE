package com.ace.consistency.controller;

import com.ace.consistency.check.DuplicateConsistencyCheck;
import com.ace.consistency.check.StockConsistencyCheck;
import com.ace.consistency.common.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 정합성 검증을 수동으로 트리거하는 온디맨드 API (Coupon State API의 일부).
 *
 * 이 컨트롤러를 호출하면:
 *   1) ConsistencyVerificationRunner가 Check들을 실행하고
 *   2) 실행 끝나는 즉시 Runner 내부에서 VerificationRepository.saveAll()이 호출되어
 *   3) verification_result 테이블에 실제로 결과가 쌓인다.
 *
 * 별도로 저장 로직을 여기서 호출할 필요가 없다 — Runner.run()이 이미 저장까지 책임진다.
 *
 * ※ 패키지 경로(com.ace.consistency.*)는 현재 프로젝트 구조를 기준으로 작성했습니다.
 *    실제 Runner/Check/공통 클래스들이 있는 패키지와 다르면 import만 맞춰주세요.
 */
@RestController
@RequestMapping("/internal/coupon-state")
@RequiredArgsConstructor
public class ConsistencyVerificationController {

	private final ConsistencyVerificationRunner runner;
	private final StockConsistencyCheck stockConsistencyCheck;
	private final DuplicateConsistencyCheck duplicateConsistencyCheck;

	/** 전체 이벤트 대상으로 재고/중복 정합성 검증을 실행하고 결과를 DB에 저장한다. */
	@PostMapping("/verify")
	public List<VerificationResult> verifyAll() {
		List<ConsistencyCheck> checks = List.of(stockConsistencyCheck, duplicateConsistencyCheck);
		return runner.run(checks, Scope.all(), TriggerType.ON_DEMAND);
	}

	/** 특정 event_id 하나만 대상으로 검증을 실행하고 결과를 DB에 저장한다. */
	@PostMapping("/verify/event/{eventId}")
	public List<VerificationResult> verifyEvent(@PathVariable Long eventId) {
		List<ConsistencyCheck> checks = List.of(stockConsistencyCheck, duplicateConsistencyCheck);
		return runner.run(checks, Scope.ofEvent(eventId), TriggerType.ON_DEMAND);
	}
}