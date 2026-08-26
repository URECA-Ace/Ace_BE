package com.ace.consistency.recovery;

import java.util.Map;

import com.ace.consistency.common.Scope;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * ConsistencyRecoveryPolicy.recover()의 실행 결과.
 *
 * revalidationScope는 복구 직후 재검증에 사용할 범위다. 원본 VerificationResult의 scope를
 * 그대로 재사용하면(특히 ALL/AS_OF_RANGE처럼 여러 이벤트를 아우르는 스코프) 비용이 크고
 * "이 복구가 실제로 무엇을 해결했는가"와도 맞지 않으므로, 정책이 자신이 실제로 손댄 대상
 * (보통 이벤트 하나)만 좁혀서 채운다.
 */
@Getter
@Builder
@AllArgsConstructor
public class RecoveryOutcome {

	private final RecoveryResultStatus status;
	private final Map<String, Object> detail;
	private final String message;
	private final Scope revalidationScope;

	public static RecoveryOutcome success(Scope revalidationScope, Map<String, Object> detail, String message) {
		return new RecoveryOutcome(RecoveryResultStatus.SUCCESS, detail, message, revalidationScope);
	}

	public static RecoveryOutcome failure(Scope revalidationScope, Map<String, Object> detail, String message) {
		return new RecoveryOutcome(RecoveryResultStatus.FAIL, detail, message, revalidationScope);
	}
}
