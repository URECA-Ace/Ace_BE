package com.ace.consistency.recovery.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResult;
import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.recovery.RecoveryOutcome;
import com.ace.consistency.recovery.RowLevelRecoveryRepository;
import com.ace.consistency.recovery.RowLevelRecoveryRepository.IssueSnapshot;
import com.ace.consistency.recovery.enums.RecoveryResultStatus;

@ExtendWith(MockitoExtension.class)
class RowLevelRecoveryExecutorTest {

	@Mock
	private RowLevelRecoveryRepository repository;

	@Test
	void 두_Executor는_각_issue를_REQUIRES_NEW로_실행한다() throws Exception {
		Method history = CouponIssueHistoryStateRecoveryExecutor.class
				.getMethod("recoverIssue", VerificationResultEntity.class, long.class);
		Method expiration = CouponExpirationLagRecoveryExecutor.class
				.getMethod("recoverIssue", VerificationResultEntity.class, long.class);

		assertThat(history.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRES_NEW);
		assertThat(expiration.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRES_NEW);
	}

	@Test
	void NO_HISTORY_ISSUED는_최초_History를_복원한다() {
		IssueSnapshot issue = issue("ISSUED", null, null, LocalDateTime.now().plusHours(1));
		given(repository.findIssueForUpdate(1L)).willReturn(Optional.of(issue));
		given(repository.countHistories(1L)).willReturn(0);
		given(repository.initialHistoryRestored(1L, issue.requestId())).willReturn(false, true);
		given(repository.historyEventUidExists(issue.requestId())).willReturn(false);

		RecoveryOutcome outcome = new CouponIssueHistoryStateRecoveryExecutor(repository)
				.recoverIssue(target("CouponIssueHistoryStateConsistencyCheck"), 1L);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		assertThat(outcome.getDetail()).containsKeys("before", "after", "issueId", "violationType");
		verify(repository).insertHistory(eq(1L), eq(null), eq("ISSUED"), eq("SYSTEM"), eq("ISSUE_CONFIRMED"),
				eq(issue.issuedAt()), any(LocalDateTime.class), eq(issue.requestId()));
	}

	@Test
	void 사용_또는_취소_흔적이_있으면_복구하지_않는다() {
		IssueSnapshot issue = issue("ISSUED", LocalDateTime.now(), null, LocalDateTime.now().plusHours(1));
		given(repository.findIssueForUpdate(1L)).willReturn(Optional.of(issue));
		given(repository.countHistories(1L)).willReturn(0);

		RecoveryOutcome outcome = new CouponIssueHistoryStateRecoveryExecutor(repository)
				.recoverIssue(target("CouponIssueHistoryStateConsistencyCheck"), 1L);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		verify(repository, never()).insertHistory(any(Long.class), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void 이미_만료_복구된_issue는_noop_SUCCESS다() {
		IssueSnapshot issue = issue("EXPIRED", null, null, LocalDateTime.now().minusHours(2));
		given(repository.findIssueForUpdate(1L)).willReturn(Optional.of(issue));
		given(repository.hasExpirationRecoveryHistory(1L)).willReturn(true);
		given(repository.countHistories(1L)).willReturn(2);

		RecoveryOutcome outcome = new CouponExpirationLagRecoveryExecutor(repository, 1_800_000)
				.recoverIssue(target("CouponExpirationLagConsistencyCheck"), 1L);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		assertThat(outcome.getDetail()).containsEntry("catchUp", true);
		verify(repository, never()).expireIfStillIssued(1L);
	}

	@Test
	void 음수_allowedDelayMillis는_생성시_거부한다() {
		assertThatThrownBy(() -> new CouponExpirationLagRecoveryExecutor(repository, -1L))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 나노초_변환이_overflow하는_allowedDelayMillis는_생성시_거부한다() {
		long overflow = Long.MAX_VALUE / 1_000_000L + 1;

		assertThatThrownBy(() -> new CouponExpirationLagRecoveryExecutor(repository, overflow))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 만료_복구_예외_메시지는_DB_정보를_노출하지_않는다() {
		given(repository.findIssueForUpdate(1L))
				.willThrow(new IllegalStateException("Table 'coupon_issue' constraint 'fk_test' failed"));

		RecoveryOutcome outcome = new CouponExpirationLagRecoveryExecutor(repository, 1_800_000L)
				.recoverIssue(target("CouponExpirationLagConsistencyCheck"), 1L);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		assertThat(outcome.getMessage()).doesNotContain("coupon_issue", "constraint", "fk_test");
	}

	@Test
	void 최초_발급_이력_복구_예외_메시지는_DB_정보를_노출하지_않는다() {
		given(repository.findIssueForUpdate(1L))
				.willThrow(new IllegalStateException("Table 'coupon_issue' constraint 'fk_test' failed"));

		RecoveryOutcome outcome = new CouponIssueHistoryStateRecoveryExecutor(repository)
				.recoverIssue(target("CouponIssueHistoryStateConsistencyCheck"), 1L);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		assertThat(outcome.getMessage()).doesNotContain("coupon_issue", "constraint", "fk_test");
	}

	private VerificationResultEntity target(String checkName) {
		return VerificationResultEntity.from(VerificationResult.fail(
				checkName, TriggerType.ON_DEMAND, Scope.ofEvent(10L), 1, Map.of(), java.util.List.of(),
				LocalDateTime.now(), 1L));
	}

	private IssueSnapshot issue(String status, LocalDateTime usedAt, LocalDateTime canceledAt,
			LocalDateTime validTo) {
		LocalDateTime issuedAt = LocalDateTime.now().minusHours(3);
		return new IssueSnapshot(1L, 10L, 20L, 1, UUID.randomUUID().toString(), null, status,
				issuedAt, issuedAt, validTo, usedAt, canceledAt, issuedAt);
	}
}
