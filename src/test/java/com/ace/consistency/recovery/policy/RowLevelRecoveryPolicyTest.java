package com.ace.consistency.recovery.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ace.common.exception.ConsistencyCheckException;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResult;
import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.recovery.RecoveryOutcome;
import com.ace.consistency.recovery.enums.RecoveryAction;

@ExtendWith(MockitoExtension.class)
class RowLevelRecoveryPolicyTest {

	@Mock CouponIssueHistoryStateRecoveryExecutor historyExecutor;
	@Mock CouponExpirationLagRecoveryExecutor expirationExecutor;

	@Test
	void 이력상태_Policy는_NO_HISTORY_issue만_distinct로_복구한다() {
		VerificationResultEntity target = fail("CouponIssueHistoryStateConsistencyCheck", List.of(
				Map.of("issue_id", 1L, "violation_type", "NO_HISTORY"),
				Map.of("issue_id", 1L, "violation_type", "NO_HISTORY"),
				Map.of("issue_id", 2L, "violation_type", "LATEST_STATUS_MISMATCH")));
		RecoveryOutcome outcome = RecoveryOutcome.success(Scope.ofEvent(10L), Map.of(), "ok");
		given(historyExecutor.recoverIssue(target, 1L)).willReturn(outcome);

		List<RecoveryOutcome> results = new CouponIssueHistoryStateRecoveryPolicy(historyExecutor)
				.recover(target, RecoveryAction.RESTORE_INITIAL_ISSUE_HISTORY);

		assertThat(results).containsExactly(outcome);
		verify(historyExecutor).recoverIssue(target, 1L);
	}

	@Test
	void 만료_Policy는_EXPIRATION_BATCH_DELAY만_복구한다() {
		VerificationResultEntity target = fail("CouponExpirationLagConsistencyCheck", List.of(
				Map.of("issue_id", 3L, "violation_type", "EXPIRATION_BATCH_DELAY"),
				Map.of("issue_id", 4L, "violation_type", "USED_AFTER_EXPIRATION")));
		RecoveryOutcome outcome = RecoveryOutcome.success(Scope.ofEvent(10L), Map.of(), "ok");
		given(expirationExecutor.recoverIssue(target, 3L)).willReturn(outcome);

		List<RecoveryOutcome> results = new CouponExpirationLagRecoveryPolicy(expirationExecutor)
				.recover(target, RecoveryAction.EXPIRE_DELAYED_ISSUE);

		assertThat(results).containsExactly(outcome);
		verify(expirationExecutor).recoverIssue(target, 3L);
	}

	@Test
	void Action이_Check와_맞지_않으면_복구하지_않는다() {
		VerificationResultEntity target = fail("CouponIssueHistoryStateConsistencyCheck", List.of(
				Map.of("issue_id", 1L, "violation_type", "NO_HISTORY")));

		assertThatThrownBy(() -> new CouponIssueHistoryStateRecoveryPolicy(historyExecutor)
				.recover(target, RecoveryAction.EXPIRE_DELAYED_ISSUE))
				.isInstanceOf(ConsistencyCheckException.class);
		verifyNoInteractions(historyExecutor);
	}

	@Test
	void availableActions는_Check별_Action만_노출한다() {
		assertThat(new CouponIssueHistoryStateRecoveryPolicy(historyExecutor).availableActions())
				.containsExactly(RecoveryAction.RESTORE_INITIAL_ISSUE_HISTORY);
		assertThat(new CouponExpirationLagRecoveryPolicy(expirationExecutor).availableActions())
				.containsExactly(RecoveryAction.EXPIRE_DELAYED_ISSUE);
	}

	private VerificationResultEntity fail(String checkName, List<Map<String, Object>> sample) {
		return VerificationResultEntity.from(VerificationResult.fail(
				checkName, TriggerType.ON_DEMAND, Scope.ofEvent(10L), sample.size(),
				Map.of("sample", sample), LocalDateTime.now(), 1L));
	}
}
