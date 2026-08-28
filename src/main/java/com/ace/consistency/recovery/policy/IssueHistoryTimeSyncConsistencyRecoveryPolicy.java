package com.ace.consistency.recovery.policy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.ace.common.ErrorCode;
import com.ace.common.exception.ConsistencyCheckException;
import com.ace.consistency.common.Scope;
import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.recovery.RecoveryOutcome;
import com.ace.consistency.recovery.enums.RecoveryAction;
import com.ace.coupon.repository.CouponEventRepository;
import com.ace.coupon.repository.CouponHistoryRepository;
import com.ace.coupon.repository.CouponIssueRepository;
import com.ace.consistency.repository.VerificationViolationRepository;
import com.ace.consistency.entity.VerificationViolationEntity;

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
	private final CouponIssueRepository couponIssueRepository;
	private final VerificationViolationRepository verificationViolationRepository;
	private final EventLogRecoveryExecutor eventLogRecoveryExecutor;

	@Override
	public String checkName() {
		return "IssueHistoryTimeSyncConsistencyCheck";
	}

	@Override
	public List<RecoveryAction> availableActions() {
		return List.of(RecoveryAction.DEFAULT, RecoveryAction.SYNC_TIME_TO_HISTORY);
	}

	@Override
	public List<RecoveryOutcome> recover(VerificationResultEntity target, RecoveryAction action) {
		List<Long> eventIds = resolveEventIds(target);
		List<RecoveryOutcome> outcomes = new ArrayList<>();
		for (Long eventId : eventIds) {
			outcomes.add(recoverOneEvent(eventId, action));
		}
		return outcomes;
	}

	private RecoveryOutcome recoverOneEvent(Long eventId, RecoveryAction action) {
		if (action != RecoveryAction.DEFAULT && action != RecoveryAction.SYNC_TIME_TO_HISTORY) {
			return RecoveryOutcome.failure(Scope.ofEvent(eventId), Map.of(),
					checkName() + "는 DEFAULT 또는 SYNC_TIME_TO_HISTORY 액션만 지원합니다.");
		}
		return eventLogRecoveryExecutor.syncTimeHistory(eventId);
	}

	/**
	 * EVENT 스코프는 target 자체가 이벤트를 특정한다. ALL 스코프는 target만으로 대상을 알 수
	 * 없으므로, 그 체크를 실행했을 때 저장해둔 diffDetail.sample(위반 건 목록)에서 eventId를
	 * 뽑아 위반 이벤트 목록을 복원한다. sample은 SAMPLE_LIMIT 없이 위반 전체를 담고 있으므로
	 * 이 목록이 곧 실제 위반 이벤트 전체와 같다.
	 */
	private List<Long> resolveEventIds(VerificationResultEntity target) {
		if (target.getScopeType() == Scope.ScopeType.EVENT) {
			return List.of(target.getEventId());
		}

		List<VerificationViolationEntity> violations = verificationViolationRepository.findByVerificationResultId(target.getId());
		if (violations.isEmpty()) {
			throw new ConsistencyCheckException(ErrorCode.RECOVERY_TARGET_EVENTS_NOT_FOUND);
		}

		List<Long> issueIds = violations.stream()
				.map(VerificationViolationEntity::getTargetId)
				.toList();

		return couponIssueRepository.findAllById(issueIds).stream()
				.map(issue -> issue.getCouponEvent().getId())
				.distinct()
				.toList();
	}

}
