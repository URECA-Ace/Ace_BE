package com.ace.consistency.recovery.policy;

import java.util.ArrayList;
import java.util.List;

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
 * StockConsistencyCheck(FAIL) 위반에 대한 복구 정책.
 *
 * 위반 원인은 두 가지이고, 각각 다른 RecoveryAction으로 대응한다.
 * - STOCK_RECONCILE_COUNTER: coupon_issue(원본)의 실제 활성 발급 건수와 coupon_event에
 *   캐시된 카운터가 어긋난 "카운터 표류". coupon_issue는 건드리지 않고 카운터만 다시 계산한다.
 * - STOCK_REVOKE_EXCESS_ISSUANCE: 실제 활성 발급 건수가 total_stock을 넘어선 "진짜 초과발급".
 *   가장 최근에 발급된 ISSUED 건부터 초과분만큼 CANCELED로 되돌려 슬롯을 반납한다.
 *
 * target이 ALL 스코프여도(여러 이벤트를 한 번에 검증한 배치 결과) 이 정책은 target에서 직접
 * 위반 이벤트 목록을 뽑아내 이벤트마다 복구를 수행하고, 이벤트별 RecoveryOutcome을 모아 리스트로
 * 반환한다. 실제 복구 작업은 StockEventRecoveryExecutor에게 이벤트 단위로 위임하는데, 그 쪽이
 * 이벤트마다 별도의 물리 트랜잭션(REQUIRES_NEW)에서 실행되므로 한 이벤트의 복구 실패가 다른
 * 이벤트의 커밋을 되돌리지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StockConsistencyRecoveryPolicy implements ConsistencyRecoveryPolicy {

	private final StockEventRecoveryExecutor eventRecoveryExecutor;
	private final VerificationViolationRepository violationRepository;

	@Override
	public String checkName() {
		return "StockConsistencyCheck";
	}

	@Override
	public List<RecoveryAction> availableActions() {
		return List.of(RecoveryAction.STOCK_RECONCILE_COUNTER, RecoveryAction.STOCK_REVOKE_EXCESS_ISSUANCE);
	}

	@Override
	public List<RecoveryOutcome> recover(VerificationResultEntity target, RecoveryAction action) {
		List<Long> eventIds = resolveEventIds(target);
		List<RecoveryOutcome> outcomes = new ArrayList<>();
		for (Long eventId : eventIds) {
			outcomes.add(eventRecoveryExecutor.recoverEvent(eventId, action));
		}
		return outcomes;
	}

	/**
	 * EVENT 스코프는 target 자체가 이벤트를 특정한다. ALL 스코프는 target만으로 대상을 알 수
	 * 없으므로, 그 체크를 실행했을 때 verification_violation에 저장해둔 위반 행 전체에서
	 * eventId를 뽑아 위반 이벤트 목록을 복원한다. 표본이 아닌 verificationResultId 기준
	 * 전체 행을 조회하므로 이 목록이 곧 실제 위반 이벤트 전체와 같다.
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
