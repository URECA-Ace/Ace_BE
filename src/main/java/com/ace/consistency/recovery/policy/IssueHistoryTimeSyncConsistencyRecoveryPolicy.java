package com.ace.consistency.recovery.policy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.ace.common.ErrorCode;
import com.ace.common.exception.ConsistencyCheckException;
import com.ace.consistency.common.Scope;
import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.entity.VerificationViolationEntity;
import com.ace.consistency.recovery.RecoveryOutcome;
import com.ace.consistency.recovery.enums.RecoveryAction;
import com.ace.consistency.repository.VerificationViolationRepository;
import com.ace.coupon.entity.CouponHistory;
import com.ace.coupon.entity.CouponIssue;
import com.ace.coupon.enums.CouponIssueStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * IssueHistoryTimeSyncConsistencyCheck(FAIL) 위반에 대한 복구 정책: "더 늦은(최신) 시각을 가진 쪽이 이긴다."
 *
 * status는 절대 건드리지 않는다(이 체커는 coupon_issue.status와 최신 history.to_status가 이미 같을
 * 때만 발동하므로 재사용 위험이 없다). coupon_issue의 시각과 coupon_history 최신 시각 중 더 늦은
 * 쪽으로 이른 쪽을 맞춘다 — 어느 테이블이 "정답"인지 미리 정해두지 않고 매번 실제 값을 보고 판단한다.
 *
 * EXPIRED 상태는 자동 복구 대상에서 제외한다 — valid_to는 발급 시점에 고정되는 파생값이라
 * "더 늦은 쪽"으로 우열을 가릴 대상이 아니고, 배치 지연/오작동 신호일 수 있어 사람이 봐야 한다.
 * 최초 발급이 아닌 재진입(복원) 케이스도 이 로직으로는 판정할 수 없어 회수 불가 목록으로 남긴다.
 *
 * target이 ALL 스코프여도(여러 이벤트를 한 번에 검증한 배치 결과) 이 정책은 target에서 직접
 * 위반 이벤트 목록을 뽑아내 이벤트마다 복구를 수행하고, 이벤트별 RecoveryOutcome을 모아 리스트로
 * 반환한다. 한 이벤트의 복구 실패가 다른 이벤트의 복구를 막지 않도록 이벤트 단위로 개별 처리한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IssueHistoryTimeSyncConsistencyRecoveryPolicy implements ConsistencyRecoveryPolicy {

	private static final Duration THRESHOLD = Duration.ofSeconds(1);
	private static final List<CouponIssueStatus> SYNCABLE_STATUSES = List.of(CouponIssueStatus.ISSUED, CouponIssueStatus.USED);

	private final EventLogRecoveryExecutor eventRecoveryExecutor;
	private final VerificationViolationRepository violationRepository;

	@Override
	public String checkName() {
		return "IssueHistoryTimeSyncConsistencyCheck";
	}

	@Override
	public List<RecoveryAction> availableActions() {
		return List.of(RecoveryAction.SYNC_TIME_TO_HISTORY);
	}

	@Override
	public List<RecoveryOutcome> recover(VerificationResultEntity target, RecoveryAction action) {
		List<Long> eventIds = resolveEventIds(target);
		List<RecoveryOutcome> outcomes = new ArrayList<>();
		for (Long eventId : eventIds) {
			if (action != RecoveryAction.SYNC_TIME_TO_HISTORY) {
				outcomes.add(RecoveryOutcome.failure(Scope.ofEvent(eventId), Map.of(),
						"IssueHistoryTimeSyncConsistencyCheck는 지원하지 않는 액션입니다."));
				continue;
			}
			outcomes.add(eventRecoveryExecutor.syncTimeHistory(eventId));
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
