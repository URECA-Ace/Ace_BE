package com.ace.consistency.recovery.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
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

	@Mock RowLevelRecoveryRepository repository;

	@Test
	void issue별_Executor는_REQUIRES_NEW_트랜잭션을_사용한다() throws Exception {
		Method history = CouponIssueHistoryStateRecoveryExecutor.class
				.getMethod("recoverIssue", VerificationResultEntity.class, long.class);
		Method expiration = CouponExpirationLagRecoveryExecutor.class
				.getMethod("recoverIssue", VerificationResultEntity.class, long.class);

		assertThat(history.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRES_NEW);
		assertThat(expiration.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRES_NEW);
	}

	@Test
	void 초기이력_복구는_사용취소_흔적이_있으면_FAIL이다() {
		IssueSnapshot issue = issue("ISSUED", null, LocalDateTime.now(), LocalDateTime.now().plusHours(1));
		given(repository.findIssueForUpdate(1L)).willReturn(Optional.of(issue));
		given(repository.countHistories(1L)).willReturn(0);

		RecoveryOutcome outcome = new CouponIssueHistoryStateRecoveryExecutor(repository)
				.recoverIssue(target("CouponIssueHistoryStateConsistencyCheck"), 1L);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		verify(repository, never()).insertHistory(any(Long.class), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void 초기이력이_이미_정상적으로_존재하면_noop_SUCCESS이다() {
		IssueSnapshot issue = issue("ISSUED", null, null, LocalDateTime.now().plusHours(1));
		given(repository.findIssueForUpdate(1L)).willReturn(Optional.of(issue));
		given(repository.countHistories(1L)).willReturn(1);
		given(repository.initialHistoryRestored(1L, issue.requestId())).willReturn(true);

		RecoveryOutcome outcome = new CouponIssueHistoryStateRecoveryExecutor(repository)
				.recoverIssue(target("CouponIssueHistoryStateConsistencyCheck"), 1L);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		assertThat(outcome.getDetail()).containsEntry("catchUp", true);
		verify(repository, never()).insertHistory(any(Long.class), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void 정상_NO_HISTORY는_이력을_복원하고_before_after를_남긴다() {
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
	void 이미_정상_만료이력까지_존재하면_noop_SUCCESS이다() {
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

	private VerificationResultEntity target(String checkName) {
		return VerificationResultEntity.from(VerificationResult.fail(
				checkName, TriggerType.ON_DEMAND, Scope.ofEvent(10L), 1,
				java.util.Map.of("sample", java.util.List.of()), LocalDateTime.now(), 1L));
	}

	private IssueSnapshot issue(String status, LocalDateTime usedAt, LocalDateTime canceledAt,
			LocalDateTime validTo) {
		LocalDateTime issuedAt = LocalDateTime.now().minusHours(3);
		return new IssueSnapshot(1L, 10L, 20L, 1, UUID.randomUUID().toString(), null, status,
				issuedAt, issuedAt, validTo, usedAt, canceledAt, issuedAt);
	}
}
