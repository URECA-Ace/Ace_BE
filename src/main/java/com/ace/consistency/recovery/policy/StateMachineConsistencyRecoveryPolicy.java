package com.ace.consistency.recovery.policy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.ace.common.ErrorCode;
import com.ace.common.exception.ConsistencyCheckException;
import com.ace.consistency.common.Scope;
import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.entity.VerificationViolationEntity;
import com.ace.consistency.recovery.RecoveryOutcome;
import com.ace.consistency.recovery.enums.RecoveryAction;
import com.ace.consistency.repository.VerificationViolationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * StateMachineConsistencyCheck(FAIL) 위반에 대한 복구 정책.
 *
 * coupon_history 이력 체인이 끊긴 지점부터 뒤를 전부 삭제하고, coupon_issue.status를 끊기기
 * 직전 상태로 되돌린다. 단, 삭제 대상 범위 안에 USED 또는 EXPIRED로의 전이가 하나라도 있으면
 * 그 발급 건은 절대 자동 복구하지 않는다 — "실제로 있었던 사용/만료 사실"을 지워서 쿠폰을
 * 재사용 가능하게(또는 만료된 쿠폰을 부활) 만들 위험이 있기 때문이다. 이 경우는 회수 불가 목록으로
 * 남겨 관리자가 원본을 직접 확인하게 한다(StockConsistencyRecoveryPolicy의 회수 불가 처리와 같은 패턴).
 *
 * 이벤트 하나의 diffDetail은 참고하지 않는다 — 감지 시점과 복구 시점 사이에 상태가 바뀔 수 있으므로,
 * eventId에 속한 모든 발급 건의 이력을 매번 다시 조회해서 "지금" 끊긴 체인만 대상으로 삼는다. target의
 * diffDetail은 오직 ALL 스코프에서 "어느 이벤트들을 복구할지"를 정하는 데만 쓴다.
 *
 * target이 ALL 스코프여도(여러 이벤트를 한 번에 검증한 배치 결과) 이 정책은 target에서 직접
 * 위반 이벤트 목록을 뽑아내 이벤트마다 복구를 수행하고, 이벤트별 RecoveryOutcome을 모아 리스트로
 * 반환한다. 한 이벤트의 복구 실패가 다른 이벤트의 복구를 막지 않도록 이벤트 단위로 개별 처리한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StateMachineConsistencyRecoveryPolicy implements ConsistencyRecoveryPolicy {

	private final EventLogRecoveryExecutor eventRecoveryExecutor;
	private final VerificationViolationRepository violationRepository;

	@Override
	public String checkName() {
		return "StateMachineConsistencyCheck";
	}

	@Override
	public List<RecoveryAction> availableActions() {
		return List.of(RecoveryAction.RESTORE_STATE_MACHINE);
	}

	@Override
	public List<RecoveryOutcome> recover(VerificationResultEntity target, RecoveryAction action) {
		List<Long> eventIds = resolveEventIds(target);
		List<RecoveryOutcome> outcomes = new ArrayList<>();
		for (Long eventId : eventIds) {
			if (action != RecoveryAction.RESTORE_STATE_MACHINE) {
				outcomes.add(RecoveryOutcome.failure(Scope.ofEvent(eventId), Map.of(),
						"StateMachineConsistencyCheck는 지원하지 않는 액션입니다."));
				continue;
			}
			outcomes.add(eventRecoveryExecutor.restoreStateMachine(eventId));
		}
		return outcomes;
	}

	/**
	 * EVENT 스코프는 target 자체가 이벤트를 특정한다. ALL 스코프는 target만으로 대상을 알 수
	 * 없으므로, verification_violation에 별도로 저장된 위반 행(위반 1건 = 행 1개) 전체에서
	 * targetId를 뽑아 위반 이벤트 목록을 복원한다.
	 */
	private List<Long> resolveEventIds(VerificationResultEntity target) {
		if (target.getScopeType() == Scope.ScopeType.EVENT) {
			return List.of(target.getEventId());
		}

		List<VerificationViolationEntity> violations = violationRepository.findByVerificationResultId(target.getId());
		if (violations.isEmpty()) {
			throw new ConsistencyCheckException(ErrorCode.RECOVERY_TARGET_EVENTS_NOT_FOUND);
		}

		return violations.stream()
				.map(VerificationViolationEntity::getTargetId)
				.distinct()
				.toList();
	}
}
