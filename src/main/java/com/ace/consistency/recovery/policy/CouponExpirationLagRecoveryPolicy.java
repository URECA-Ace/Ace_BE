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
public class CouponExpirationLagRecoveryPolicy implements ConsistencyRecoveryPolicy {

	private static final String CHECK_NAME = "CouponExpirationLagConsistencyCheck";
	private static final String TARGET_VIOLATION = "EXPIRATION_BATCH_DELAY";

	private final CouponExpirationLagRecoveryExecutor executor;

	@Override
	public String checkName() {
		return CHECK_NAME;
	}

	@Override
	public List<RecoveryAction> availableActions() {
		return List.of(RecoveryAction.EXPIRE_DELAYED_ISSUE);
	}

	@Override
	public List<RecoveryOutcome> recover(VerificationResultEntity target, RecoveryAction action) {
		if (action != RecoveryAction.EXPIRE_DELAYED_ISSUE) {
			throw new ConsistencyCheckException(ErrorCode.RECOVERY_NOT_APPLICABLE,
					"CouponExpirationLagConsistencyCheck는 EXPIRE_DELAYED_ISSUE 액션이 필요합니다.");
		}

		Set<Long> issueIds = extractIssueIds(target);
		List<RecoveryOutcome> outcomes = new ArrayList<>(issueIds.size());
		for (Long issueId : issueIds) {
			outcomes.add(executor.recoverIssue(target, issueId));
		}
		return outcomes;
	}

	private Set<Long> extractIssueIds(VerificationResultEntity target) {
		Object rawSample = target.getDiffDetail().get("sample");
		if (!(rawSample instanceof List<?> sample)) {
			throw new ConsistencyCheckException(ErrorCode.RECOVERY_NOT_APPLICABLE,
					"검증 결과에서 복구 대상 issue를 찾을 수 없습니다.");
		}
		Set<Long> issueIds = new LinkedHashSet<>();
		for (Object rawRow : sample) {
			if (!(rawRow instanceof Map<?, ?> row) || !TARGET_VIOLATION.equals(row.get("violation_type"))) {
				continue;
			}
			Object rawIssueId = row.get("issue_id");
			if (rawIssueId instanceof Number issueId) {
				issueIds.add(issueId.longValue());
			}
		}
		if (issueIds.isEmpty()) {
			throw new ConsistencyCheckException(ErrorCode.RECOVERY_NOT_APPLICABLE,
					"검증 결과에 EXPIRATION_BATCH_DELAY 복구 대상이 없습니다.");
		}
		return issueIds;
	}

}
