package com.ace.consistency.recovery;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.ConsistencyVerificationRunner;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResult;
import com.ace.consistency.entity.VerificationResultEntity;
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

	@Transactional
	public RecoveryResult recover(Long verificationResultId, RecoveryAction action) {
		VerificationResultEntity target = verificationResultRepository.findById(verificationResultId)
				.orElseThrow(() -> new CouponException(ErrorCode.VERIFICATION_RESULT_NOT_FOUND));

		if (target.getStatus() != VerificationResult.Status.FAIL) {
			throw new CouponException(ErrorCode.RECOVERY_NOT_APPLICABLE);
		}

		ConsistencyRecoveryPolicy policy = policiesByCheckName.get(target.getCheckName());
		if (policy == null) {
			throw new CouponException(ErrorCode.RECOVERY_POLICY_NOT_FOUND);
		}

		RecoveryOutcome outcome = policy.recover(target, action);

		RecoveryResult saved = recoveryResultRepository.save(
				RecoveryResult.from(verificationResultId, outcome, LocalDateTime.now()));

		reverify(target, outcome.getRevalidationScope());

		return saved;
	}

	private void reverify(VerificationResultEntity target, Scope revalidationScope) {
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
