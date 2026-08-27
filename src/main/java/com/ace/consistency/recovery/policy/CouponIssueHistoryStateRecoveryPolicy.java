package com.ace.consistency.recovery.policy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.ace.common.ErrorCode;
import com.ace.common.exception.ConsistencyCheckException;
import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.recovery.RecoveryOutcome;
import com.ace.consistency.recovery.enums.RecoveryAction;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CouponIssueHistoryStateRecoveryPolicy implements ConsistencyRecoveryPolicy {

	private static final String CHECK_NAME = "CouponIssueHistoryStateConsistencyCheck";
	private static final String TARGET_VIOLATION = "NO_HISTORY";

	private final CouponIssueHistoryStateRecoveryExecutor executor;

	@Override
	public String checkName() {
		return CHECK_NAME;
	}

	@Override
	public List<RecoveryAction> availableActions() {
		return List.of(RecoveryAction.RESTORE_INITIAL_ISSUE_HISTORY);
	}

	@Override
	public List<RecoveryOutcome> recover(VerificationResultEntity target, RecoveryAction action) {
		if (action != RecoveryAction.RESTORE_INITIAL_ISSUE_HISTORY) {
			throw new ConsistencyCheckException(ErrorCode.RECOVERY_NOT_APPLICABLE,
					"CouponIssueHistoryStateConsistencyCheck는 RESTORE_INITIAL_ISSUE_HISTORY 액션이 필요합니다.");
		}

		Set<Long> issueIds = extractIssueIds(target, TARGET_VIOLATION);
		List<RecoveryOutcome> outcomes = new ArrayList<>(issueIds.size());
		for (Long issueId : issueIds) {
			outcomes.add(executor.recoverIssue(target, issueId));
		}
		return outcomes;
	}

	private Set<Long> extractIssueIds(VerificationResultEntity target, String violationType) {
		Object rawSample = target.getDiffDetail().get("sample");
		if (!(rawSample instanceof List<?> sample)) {
			throw new ConsistencyCheckException(ErrorCode.RECOVERY_NOT_APPLICABLE,
					"검증 결과에서 복구 대상 issue를 찾을 수 없습니다.");
		}
		Set<Long> issueIds = new LinkedHashSet<>();
		for (Object rawRow : sample) {
			if (!(rawRow instanceof Map<?, ?> row) || !violationType.equals(row.get("violation_type"))) {
				continue;
			}
			Object rawIssueId = row.get("issue_id");
			if (rawIssueId instanceof Number issueId) {
				issueIds.add(issueId.longValue());
			}
		}
		if (issueIds.isEmpty()) {
			throw new ConsistencyCheckException(ErrorCode.RECOVERY_NOT_APPLICABLE,
					"검증 결과에 NO_HISTORY 복구 대상이 없습니다.");
		}
		return issueIds;
	}

}
