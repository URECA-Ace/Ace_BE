package com.ace.consistency.recovery.policy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.ace.common.ErrorCode;
import com.ace.common.exception.ConsistencyCheckException;
import com.ace.consistency.common.ViolationTargetType;
import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.entity.VerificationViolationEntity;
import com.ace.consistency.recovery.RecoveryOutcome;
import com.ace.consistency.recovery.enums.RecoveryAction;
import com.ace.consistency.repository.VerificationViolationRepository;

import lombok.RequiredArgsConstructor;

/** CouponExpirationLagConsistencyCheck의 EXPIRATION_BATCH_DELAY 복구 정책. */
@Component
@RequiredArgsConstructor
public class CouponExpirationLagRecoveryPolicy implements ConsistencyRecoveryPolicy {

	private static final String CHECK_NAME = "CouponExpirationLagConsistencyCheck";
	private static final String TARGET_VIOLATION = "EXPIRATION_BATCH_DELAY";

	private final CouponExpirationLagRecoveryExecutor executor;
	private final VerificationViolationRepository violationRepository;

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
		Set<Long> issueIds = new LinkedHashSet<>();
		for (VerificationViolationEntity violation : violationRepository.findByVerificationResultId(target.getId())) {
			if (violation.getTargetType() != ViolationTargetType.ISSUE
					|| violation.getTargetId() == null
					|| violation.getDetail() == null
					|| !TARGET_VIOLATION.equals(violation.getDetail().get("violation_type"))) {
				continue;
			}
			issueIds.add(violation.getTargetId());
		}
		if (issueIds.isEmpty()) {
			throw new ConsistencyCheckException(ErrorCode.RECOVERY_NOT_APPLICABLE,
					"검증 결과에 EXPIRATION_BATCH_DELAY 복구 대상이 없습니다.");
		}
		return issueIds;
	}
}
