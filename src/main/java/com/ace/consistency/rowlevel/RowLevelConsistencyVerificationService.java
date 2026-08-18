package com.ace.consistency.rowlevel;

import com.ace.consistency.common.ConsistencyVerificationRunner;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 발급 Consumer, 상태 변경 로직, 스케줄러가 네트워크 왕복 없이 호출하는 진입점이다.
 * 실제 실행·결과 저장·실패 후속 처리는 공통 Runner에 위임한다.
 */
@Service
@RequiredArgsConstructor
public class RowLevelConsistencyVerificationService {

	private final ConsistencyVerificationRunner runner;
	private final RowLevelConsistencyCheckGroup checkGroup;

	public List<VerificationResult> verifyStructural(Scope scope, TriggerType triggerType) {
		return runner.run(checkGroup.structuralChecks(), scope, triggerType);
	}

	public List<VerificationResult> verifyStateTransition(Scope scope, TriggerType triggerType) {
		return runner.run(checkGroup.stateTransitionChecks(), scope, triggerType);
	}

	public List<VerificationResult> verifyExpiration(
			LocalDateTime allowedExpirationBoundary,
			LocalDateTime snapshotAt,
			TriggerType triggerType) {
		Scope scope = Scope.ofAsOfRange(allowedExpirationBoundary, snapshotAt);
		return runner.run(checkGroup.expirationChecks(), scope, triggerType);
	}
}
