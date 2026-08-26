package com.ace.consistency.common;

import com.ace.common.exception.ConsistencyCheckException;
import com.ace.consistency.entity.VerificationResultEntity;
import lombok.*;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;

/**
 * 모든 ConsistencyCheck의 실행 결과가 공통으로 따르는 표준 포맷.
 * DB 저장(verification_result 테이블), Coupon State API 응답, 대시보드 노출에 동일하게 사용된다.
 *
 * 생성자를 private으로 감춰서, 항상 정적 팩토리 메서드(pass/fail/error)를 통해서만
 * 만들어지도록 강제한다 (Status와 diffDetail/errorMessage 조합이 어긋나는 잘못된 상태를
 * 만들 수 없게 하기 위함).
 */
@Getter
@ToString
@EqualsAndHashCode
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class VerificationResult {

	public enum Status {
		PASS,
		FAIL,
		ERROR   // Check 실행 자체가 예외로 실패한 경우 (FAIL은 "정상 실행됐지만 불일치 발견"과 구분)
	}

	private final String checkName;
	private final TriggerType triggerType;
	private final Scope scope;
	private final Status status;
	private final int violationCount;
	private final Map<String, Object> diffDetail; // 불일치 상세 (예: {"eventId":123,"expected":10000,"actual":10007})
	private final String errorMessage;             // ERROR 상태일 때만 값 존재
	private final LocalDateTime executedAt;
	private final long durationMillis;

	public static VerificationResult pass(String checkName, TriggerType triggerType, Scope scope,
										  LocalDateTime executedAt, long durationMillis) {
		return new VerificationResult(checkName, triggerType, scope, Status.PASS,
				0, Collections.emptyMap(), null, executedAt, durationMillis);
	}

	public static VerificationResult fail(String checkName, TriggerType triggerType, Scope scope,
										  int violationCount,
										  Map<String, Object> diffDetail,
										  LocalDateTime executedAt, long durationMillis) {
		return new VerificationResult(checkName, triggerType, scope, Status.FAIL,
				violationCount, Map.copyOf(diffDetail), null, executedAt, durationMillis);
	}

	public static VerificationResult error(String checkName, TriggerType triggerType, Scope scope,
										   Throwable cause,
										   LocalDateTime executedAt, long durationMillis) {
		return new VerificationResult(checkName, triggerType, scope, Status.ERROR,
				0, Collections.emptyMap(), describe(cause), executedAt, durationMillis);
	}

	// ConsistencyCheckException이면 클래스명 대신 ErrorCode로 원인을 구분한다.
	private static String describe(Throwable cause) {
		if (cause instanceof ConsistencyCheckException consistencyCheckException) {
			return consistencyCheckException.getErrorCode().name() + ": " + cause.getMessage();
		}
		return cause.getClass().getSimpleName() + ": " + cause.getMessage();
	}

	public boolean isPass() {
		return status == Status.PASS;
	}
}