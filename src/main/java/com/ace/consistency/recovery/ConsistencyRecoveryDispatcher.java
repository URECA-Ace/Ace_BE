package com.ace.consistency.recovery;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
import com.ace.consistency.recovery.enums.RecoveryResultStatus;
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
	 * 관리자 화면에서 이 검증 결과에 대해 선택 가능한 복구 액션 목록을 조회한다.
	 * 복구 정책이 아직 없는 체크도 관리자가 수동으로 확인해야 할 대상으로 화면에 노출되어야
	 * 하므로, findPolicy()처럼 예외를 던지지 않고 빈 목록을 반환한다. 실제 복구 요청(recover())은
	 * 여전히 findPolicy()를 통해 RECOVERY_POLICY_NOT_FOUND로 거부된다.
	 */
	public List<RecoveryAction> availableActions(Long verificationResultId) {
		VerificationResultEntity target = verificationResultRepository.findById(verificationResultId)
				.orElseThrow(() -> new ConsistencyCheckException(ErrorCode.VERIFICATION_RESULT_NOT_FOUND));

		ConsistencyRecoveryPolicy policy = policiesByCheckName.get(target.getCheckName());
		return policy == null ? List.of() : policy.availableActions();
	}

	/**
	 * target 하나가 얼마나 많은 위반을 담고 있고 그걸 어떤 단위(이벤트, 발급 건 등)로 나눠
	 * 복구해야 하는지는 체크마다 다르므로, Dispatcher는 그 판단을 정책에 완전히 위임한다.
	 * 정책이 반환한 RecoveryOutcome 리스트를 그대로 순회하며 각각 이력을 저장하고 재검증한다.
	 */
	@Transactional
	public List<RecoveryResult> recover(Long verificationResultId, RecoveryAction action) {
		VerificationResultEntity target = verificationResultRepository.findById(verificationResultId)
				.orElseThrow(() -> new ConsistencyCheckException(ErrorCode.VERIFICATION_RESULT_NOT_FOUND));

		if (target.getStatus() != VerificationResult.Status.FAIL) {
			throw new ConsistencyCheckException(ErrorCode.RECOVERY_NOT_APPLICABLE);
		}

		ConsistencyRecoveryPolicy policy = findPolicy(target.getCheckName());
		List<RecoveryOutcome> outcomes = policy.recover(target, action);

		List<RecoveryResult> results = new ArrayList<>();
		boolean allRecovered = true;
		for (RecoveryOutcome outcome : outcomes) {
			RecoveryResult saved = recoveryResultRepository.save(
					RecoveryResult.from(verificationResultId, target.getCheckName(), action,
							outcome, LocalDateTime.now()));
			results.add(saved);

			if (outcome.getStatus() == RecoveryResultStatus.FAIL) {
				allRecovered = false;
				continue;
			}
			if (!reverifyPassed(target, outcome.getRevalidationScope())) {
				allRecovered = false;
			}
		}

		if (allRecovered) {
			target.markRecovered();
		} else {
			target.markRecoveryFailed();
		}

		return results;
	}

	private ConsistencyRecoveryPolicy findPolicy(String checkName) {
		ConsistencyRecoveryPolicy policy = policiesByCheckName.get(checkName);
		if (policy == null) {
			throw new ConsistencyCheckException(ErrorCode.RECOVERY_POLICY_NOT_FOUND);
		}
		return policy;
	}

	/** @return 재검증이 통과했으면 true. */
	private boolean reverifyPassed(VerificationResultEntity target, Scope revalidationScope) {
		// Runner.run()은 ALL 스코프를 지원하지 않는다(ALL은 Spring Batch 비동기 전용).
		// 정책이 revalidationScope를 이벤트 단위로 좁히지 못하고 ALL을 그대로 반환하면 재검증할 수 없다.
		if (revalidationScope.getType() == Scope.ScopeType.ALL) {
			throw new ConsistencyCheckException(ErrorCode.RECOVERY_NOT_SUPPORTED_FOR_ALL_SCOPE);
		}

		ConsistencyCheck check = checksByName.get(target.getCheckName());
		List<VerificationResult> results = verificationRunner.run(
				List.of(check), revalidationScope, TriggerType.RECOVERY_REVALIDATION);

		return results.get(0).isPass();
	}
}
