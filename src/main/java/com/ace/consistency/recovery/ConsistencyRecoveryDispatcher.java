package com.ace.consistency.recovery;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ace.common.ErrorCode;
import com.ace.common.exception.ConsistencyCheckException;
import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.ConsistencyVerificationRunner;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResult;
import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.recovery.enums.RecoveryAction;
import com.ace.consistency.recovery.policy.ConsistencyRecoveryPolicy;
import com.ace.consistency.recovery.repository.RecoveryResultRepository;
import com.ace.consistency.repository.VerificationResultRepository;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

/**
 * "FAIL인 VerificationResult + 관리자가 고른 Action" -> "그 체크의 복구 정책 실행" -> "이력 저장"
 * -> "재검증" -> "VerificationResult 상태 갱신"으로 이어지는 공통 흐름의 단일 진입점.
 *
 * 관리자 API(수동 트리거)와 시스템 자동 트리거(예: 재고 초과발급 감지 즉시 자동 회수)가
 * 트리거 종류와 무관하게 이 recover() 하나를 통해서만 복구를 수행한다.
 */
@Component
@RequiredArgsConstructor
public class ConsistencyRecoveryDispatcher {

	private final VerificationResultRepository verificationResultRepository;
	private final RecoveryResultRepository recoveryResultRepository;
	private final ConsistencyVerificationRunner verificationRunner;
	private final List<ConsistencyRecoveryPolicy> recoveryPolicies;
	private final List<ConsistencyCheck> checks;

	private Map<String, ConsistencyRecoveryPolicy> policiesByCheckName;
	private Map<String, ConsistencyCheck> checksByName;

	@PostConstruct
	void index() {
		policiesByCheckName = recoveryPolicies.stream()
				.collect(Collectors.toMap(ConsistencyRecoveryPolicy::checkName, Function.identity()));
		checksByName = checks.stream()
				.collect(Collectors.toMap(ConsistencyCheck::getName, Function.identity()));
	}

	/**
	 * @param eventId ALL 스코프 검증 결과를 복구할 때, 그 안의 여러 위반 이벤트 중 어느 것을 복구할지
	 *                호출부(관리자 UI/배치)가 명시적으로 지정한다. EVENT 스코프 결과는 target 자체가
	 *                이벤트를 특정하므로 무시된다(null이어도 된다).
	 */
	@Transactional
	public RecoveryResult recover(Long verificationResultId, RecoveryAction action, Long eventId) {
		VerificationResultEntity target = verificationResultRepository.findById(verificationResultId)
				.orElseThrow(() -> new ConsistencyCheckException(ErrorCode.VERIFICATION_RESULT_NOT_FOUND));

		if (target.getStatus() != VerificationResult.Status.FAIL) {
			throw new ConsistencyCheckException(ErrorCode.RECOVERY_NOT_APPLICABLE);
		}

		ConsistencyRecoveryPolicy policy = policiesByCheckName.get(target.getCheckName());
		if (policy == null) {
			throw new ConsistencyCheckException(ErrorCode.RECOVERY_POLICY_NOT_FOUND);
		}

		Long resolvedEventId = resolveEventId(target, eventId);
		RecoveryOutcome outcome = policy.recover(target, action, resolvedEventId);

		RecoveryResult saved = recoveryResultRepository.save(
				RecoveryResult.from(verificationResultId, outcome, LocalDateTime.now()));

		reverify(target, outcome.getRevalidationScope());

		return saved;
	}

	/**
	 * target이 ALL 스코프여도(예: 배치로 여러 이벤트를 한 번에 검증한 결과) 위반은 이벤트 단위로
	 * 존재하므로, 어느 이벤트를 복구할지는 항상 이벤트 하나로 확정지어 정책에 넘긴다.
	 * EVENT 스코프는 target이 이미 이벤트를 특정하므로 호출부가 넘긴 eventId는 쓰지 않고,
	 * ALL 스코프는 target만으로 대상을 알 수 없으므로 호출부가 반드시 eventId를 지정해야 한다.
	 */
	private Long resolveEventId(VerificationResultEntity target, Long eventId) {
		if (target.getScopeType() == Scope.ScopeType.EVENT) {
			return target.getEventId();
		}
		if (eventId == null) {
			throw new ConsistencyCheckException(ErrorCode.RECOVERY_EVENT_ID_REQUIRED);
		}
		return eventId;
	}

	private void reverify(VerificationResultEntity target, Scope revalidationScope) {
		// Runner.run()은 ALL 스코프를 지원하지 않는다(ALL은 Spring Batch 비동기 전용).
		// 정책이 revalidationScope를 이벤트 단위로 좁히지 못하고 ALL을 그대로 반환하면 재검증할 수 없다.
		if (revalidationScope.getType() == Scope.ScopeType.ALL) {
			throw new ConsistencyCheckException(ErrorCode.RECOVERY_NOT_SUPPORTED_FOR_ALL_SCOPE);
		}

		ConsistencyCheck check = checksByName.get(target.getCheckName());
		List<VerificationResult> results = verificationRunner.run(
				List.of(check), revalidationScope, TriggerType.RECOVERY_REVALIDATION);

		if (results.get(0).isPass()) {
			target.markRecovered();
		} else {
			target.markRecoveryFailed();
		}
	}
}
