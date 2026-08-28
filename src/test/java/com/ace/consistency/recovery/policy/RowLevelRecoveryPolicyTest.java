package com.ace.consistency.recovery.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ace.common.ErrorCode;
import com.ace.common.exception.ConsistencyCheckException;
import com.ace.consistency.common.ConsistencyCheck.Violation;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResult;
import com.ace.consistency.common.ViolationTargetType;
import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.entity.VerificationViolationEntity;
import com.ace.consistency.recovery.RecoveryOutcome;
import com.ace.consistency.recovery.enums.RecoveryAction;
import com.ace.consistency.repository.VerificationViolationRepository;

@ExtendWith(MockitoExtension.class)
class RowLevelRecoveryPolicyTest {

	@Mock
	private CouponIssueHistoryStateRecoveryExecutor historyExecutor;
	@Mock
	private CouponExpirationLagRecoveryExecutor expirationExecutor;
	@Mock
	private VerificationViolationRepository violationRepository;

	private VerificationResultEntity historyTarget;
	private VerificationResultEntity expirationTarget;

	@BeforeEach
	void setUp() {
		historyTarget = failTarget("CouponIssueHistoryStateConsistencyCheck", Scope.ofEvent(10L));
		expirationTarget = failTarget("CouponExpirationLagConsistencyCheck", Scope.ofEvent(10L));
	}

	@Test
	void NO_HISTORY_violation의_issue만_중복없이_executor에_전달한다() {
		given(violationRepository.findByVerificationResultId(historyTarget.getId())).willReturn(List.of(
				violation(ViolationTargetType.ISSUE, 1L, "NO_HISTORY"),
				violation(ViolationTargetType.ISSUE, 1L, "NO_HISTORY"),
				violation(ViolationTargetType.ISSUE, 2L, "LATEST_STATUS_MISMATCH"),
				violation(ViolationTargetType.EVENT, 3L, "NO_HISTORY"),
				violation(ViolationTargetType.ISSUE, 4L, null)));
		RecoveryOutcome outcome = RecoveryOutcome.success(Scope.ofEvent(10L), Map.of(), "ok");
		given(historyExecutor.recoverIssue(historyTarget, 1L)).willReturn(outcome);

		List<RecoveryOutcome> outcomes = new CouponIssueHistoryStateRecoveryPolicy(historyExecutor, violationRepository)
				.recover(historyTarget, RecoveryAction.RESTORE_INITIAL_ISSUE_HISTORY);

		assertThat(outcomes).containsExactly(outcome);
		verify(historyExecutor).recoverIssue(historyTarget, 1L);
	}

	@Test
	void EXPIRATION_BATCH_DELAY_violation의_issue만_중복없이_executor에_전달한다() {
		given(violationRepository.findByVerificationResultId(expirationTarget.getId())).willReturn(List.of(
				violation(ViolationTargetType.ISSUE, 5L, "EXPIRATION_BATCH_DELAY"),
				violation(ViolationTargetType.ISSUE, 5L, "EXPIRATION_BATCH_DELAY"),
				violation(ViolationTargetType.ISSUE, 6L, "USED_AFTER_EXPIRATION"),
				violation(ViolationTargetType.EVENT, 7L, "EXPIRATION_BATCH_DELAY")));
		RecoveryOutcome outcome = RecoveryOutcome.success(Scope.ofEvent(10L), Map.of(), "ok");
		given(expirationExecutor.recoverIssue(expirationTarget, 5L)).willReturn(outcome);

		List<RecoveryOutcome> outcomes = new CouponExpirationLagRecoveryPolicy(expirationExecutor, violationRepository)
				.recover(expirationTarget, RecoveryAction.EXPIRE_DELAYED_ISSUE);

		assertThat(outcomes).containsExactly(outcome);
		verify(expirationExecutor).recoverIssue(expirationTarget, 5L);
	}

	@Test
	void 위반_행이_없거나_알려지지_않은_유형이면_복구를_거부한다() {
		given(violationRepository.findByVerificationResultId(historyTarget.getId())).willReturn(List.of(
				violation(ViolationTargetType.ISSUE, 1L, "UNKNOWN")));

		assertThatThrownBy(() -> new CouponIssueHistoryStateRecoveryPolicy(historyExecutor, violationRepository)
				.recover(historyTarget, RecoveryAction.RESTORE_INITIAL_ISSUE_HISTORY))
				.isInstanceOf(ConsistencyCheckException.class)
				.extracting(ex -> ((ConsistencyCheckException) ex).getErrorCode())
				.isEqualTo(ErrorCode.RECOVERY_NOT_APPLICABLE);
	}

	@Test
	void 정책별_action이_아니면_복구를_거부한다() {
		assertThatThrownBy(() -> new CouponExpirationLagRecoveryPolicy(expirationExecutor, violationRepository)
				.recover(expirationTarget, RecoveryAction.DEFAULT))
				.isInstanceOf(ConsistencyCheckException.class)
				.extracting(ex -> ((ConsistencyCheckException) ex).getErrorCode())
				.isEqualTo(ErrorCode.RECOVERY_NOT_APPLICABLE);
	}

	private VerificationResultEntity failTarget(String checkName, Scope scope) {
		return VerificationResultEntity.from(VerificationResult.fail(
				checkName, TriggerType.ON_DEMAND, scope, 1, Map.of(), List.of(), LocalDateTime.now(), 1L));
	}

	private VerificationViolationEntity violation(ViolationTargetType targetType, long targetId, String type) {
		Map<String, Object> detail = type == null ? Map.of("issue_id", targetId) : Map.of("issue_id", targetId,
				"violation_type", type);
		return VerificationViolationEntity.forResult(1L,
				new Violation(targetType, targetId, detail));
	}
}
