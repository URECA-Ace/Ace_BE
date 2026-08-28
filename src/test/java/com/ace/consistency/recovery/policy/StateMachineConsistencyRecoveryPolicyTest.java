package com.ace.consistency.recovery.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ace.common.ErrorCode;
import com.ace.common.exception.ConsistencyCheckException;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResult;
import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.recovery.RecoveryOutcome;
import com.ace.consistency.recovery.enums.RecoveryAction;
import com.ace.consistency.recovery.enums.RecoveryResultStatus;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.entity.CouponHistory;
import com.ace.coupon.entity.CouponIssue;
import com.ace.coupon.enums.CouponIssueStatus;
import com.ace.coupon.repository.CouponEventRepository;
import com.ace.coupon.repository.CouponHistoryRepository;
import com.ace.coupon.repository.CouponIssueRepository;
import com.ace.consistency.repository.VerificationViolationRepository;
import com.ace.consistency.common.ConsistencyCheck.Violation;
import com.ace.consistency.common.ViolationTargetType;
import com.ace.consistency.entity.VerificationViolationEntity;

@ExtendWith(MockitoExtension.class)
class StateMachineConsistencyRecoveryPolicyTest {

	private static final Long EVENT_ID = 1L;

	@Mock
	private CouponIssueRepository couponIssueRepository;

	@Mock
	private CouponHistoryRepository couponHistoryRepository;

	@Mock
	private CouponEventRepository couponEventRepository;

	@Mock
	private VerificationViolationRepository verificationViolationRepository;

	private StateMachineConsistencyRecoveryPolicy policy;
	private EventLogRecoveryExecutor executor;

	@BeforeEach
	void setUp() {
		executor = new EventLogRecoveryExecutor(couponIssueRepository, couponHistoryRepository, couponEventRepository);
		policy = new StateMachineConsistencyRecoveryPolicy(couponIssueRepository, verificationViolationRepository, executor);
		// recoverBrokenChains()가 맨 먼저 거는 이벤트 락. DEFAULT_액션이_아닌 케이스처럼
		// 이 락까지 도달하지 않는 테스트도 있으므로 lenient로 미사용 스터빙 오류를 피한다.
		lenient().when(couponEventRepository.findByIdForUpdate(EVENT_ID)).thenReturn(Optional.of(mock(CouponEvent.class)));
		lenient().when(couponEventRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(mock(CouponEvent.class)));
	}

	private VerificationResultEntity failResultFor(Long eventId) {
		return VerificationResultEntity.from(VerificationResult.fail(
				"StateMachineConsistencyCheck", TriggerType.ON_DEMAND, Scope.ofEvent(eventId),
				1, Map.of(), List.of(), LocalDateTime.now(), 10L));
	}

	private CouponIssue issueWithStatus(Long id, CouponIssueStatus status) {
		return CouponIssue.builder().id(id).status(status).build();
	}

	private CouponHistory history(Long id, Long issueId, CouponIssueStatus from, CouponIssueStatus to, LocalDateTime occurredAt) {
		return CouponHistory.builder()
				.id(id)
				.couponIssue(CouponIssue.builder().id(issueId).build())
				.fromStatus(from)
				.toStatus(to)
				.occurredAt(occurredAt)
				.recordedAt(occurredAt)
				.build();
	}

	private RecoveryOutcome recoverSingle(VerificationResultEntity target, RecoveryAction action) {
		List<RecoveryOutcome> outcomes = policy.recover(target, action);
		assertThat(outcomes).hasSize(1);
		return outcomes.get(0);
	}

	@Test
	void 삭제_범위에_USED_EXPIRED가_없으면_중복된_이력을_삭제하고_상태를_원복한다() {
		LocalDateTime base = LocalDateTime.now();
		given(couponHistoryRepository.findAllByCouponEventIdOrderByIssueAndTime(EVENT_ID)).willReturn(List.of(
				history(1L, 10L, null, CouponIssueStatus.ISSUED, base),
				history(2L, 10L, null, CouponIssueStatus.ISSUED, base.plusSeconds(1)))); // 중복 삽입된 잘못된 이력
		CouponIssue lockedIssue = issueWithStatus(10L, CouponIssueStatus.ISSUED);
		given(couponIssueRepository.findByIdForUpdate(10L)).willReturn(Optional.of(lockedIssue));

		RecoveryOutcome outcome = recoverSingle(failResultFor(EVENT_ID), RecoveryAction.DEFAULT);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		assertThat(outcome.getDetail()).containsEntry("recoveredIssueIds", List.of(10L));
		verify(couponHistoryRepository, times(1)).deleteAllByIdInBatch(List.of(2L));
		assertThat(lockedIssue.getStatus()).isEqualTo(CouponIssueStatus.ISSUED);
	}

	@Test
	void 삭제_범위에_USED가_포함되면_회수하지_않고_FAIL을_반환한다() {
		LocalDateTime base = LocalDateTime.now();
		given(couponHistoryRepository.findAllByCouponEventIdOrderByIssueAndTime(EVENT_ID)).willReturn(List.of(
				history(1L, 11L, null, CouponIssueStatus.ISSUED, base),
				history(2L, 11L, CouponIssueStatus.EXPIRED, CouponIssueStatus.USED, base.plusSeconds(1)))); // 연속성 붕괴, to=USED

		RecoveryOutcome outcome = recoverSingle(failResultFor(EVENT_ID), RecoveryAction.DEFAULT);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		assertThat(outcome.getDetail()).containsEntry("notEligibleIssueIds", List.of(11L));
		verify(couponHistoryRepository, never()).deleteAllByIdInBatch(anyList());
		verify(couponIssueRepository, never()).findByIdForUpdate(11L);
	}

	@Test
	void 최초_이력_자체가_손상되면_회수하지_않고_FAIL을_반환한다() {
		LocalDateTime base = LocalDateTime.now();
		given(couponHistoryRepository.findAllByCouponEventIdOrderByIssueAndTime(EVENT_ID)).willReturn(List.of(
				history(1L, 12L, CouponIssueStatus.USED, CouponIssueStatus.ISSUED, base))); // 유일한 이력인데 from이 NULL이 아님

		RecoveryOutcome outcome = recoverSingle(failResultFor(EVENT_ID), RecoveryAction.DEFAULT);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		assertThat(outcome.getDetail()).containsEntry("notEligibleIssueIds", List.of(12L));
	}

	@Test
	void 이미_정상_체인이면_아무것도_건드리지_않고_SUCCESS를_반환한다() {
		LocalDateTime base = LocalDateTime.now();
		given(couponHistoryRepository.findAllByCouponEventIdOrderByIssueAndTime(EVENT_ID)).willReturn(List.of(
				history(1L, 13L, null, CouponIssueStatus.ISSUED, base),
				history(2L, 13L, CouponIssueStatus.ISSUED, CouponIssueStatus.USED, base.plusSeconds(1))));

		RecoveryOutcome outcome = recoverSingle(failResultFor(EVENT_ID), RecoveryAction.DEFAULT);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		assertThat(outcome.getDetail()).containsEntry("recoveredIssueIds", List.of());
		verify(couponHistoryRepository, never()).deleteAllByIdInBatch(anyList());
	}

	@Test
	void 이벤트에_붕괴된_체인이_없으면_아무것도_하지_않고_SUCCESS를_반환한다() {
		given(couponHistoryRepository.findAllByCouponEventIdOrderByIssueAndTime(EVENT_ID)).willReturn(List.of());

		RecoveryOutcome outcome = recoverSingle(failResultFor(EVENT_ID), RecoveryAction.DEFAULT);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		assertThat(outcome.getMessage()).contains("붕괴 상태가 아닙니다");
	}

	@Test
	void 같은_이벤트에_회수_가능한_건과_불가능한_건이_섞여있으면_가능한_것만_회수하고_FAIL을_반환한다() {
		LocalDateTime base = LocalDateTime.now();
		given(couponHistoryRepository.findAllByCouponEventIdOrderByIssueAndTime(EVENT_ID)).willReturn(List.of(
				history(1L, 20L, null, CouponIssueStatus.ISSUED, base),
				history(2L, 20L, null, CouponIssueStatus.ISSUED, base.plusSeconds(1)), // 20번: 회수 가능
				history(3L, 21L, null, CouponIssueStatus.ISSUED, base),
				history(4L, 21L, CouponIssueStatus.EXPIRED, CouponIssueStatus.USED, base.plusSeconds(1)))); // 21번: 회수 불가
		given(couponIssueRepository.findByIdForUpdate(20L))
				.willReturn(Optional.of(issueWithStatus(20L, CouponIssueStatus.ISSUED)));

		RecoveryOutcome outcome = recoverSingle(failResultFor(EVENT_ID), RecoveryAction.DEFAULT);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		assertThat(outcome.getDetail()).containsEntry("recoveredIssueIds", List.of(20L));
		assertThat(outcome.getDetail()).containsEntry("notEligibleIssueIds", List.of(21L));
		verify(couponIssueRepository, never()).findByIdForUpdate(21L);
	}

	@Test
	void DEFAULT_액션이_아니면_FAIL_Outcome을_반환하고_아무것도_조회하지_않는다() {
		RecoveryOutcome outcome = recoverSingle(failResultFor(EVENT_ID), RecoveryAction.STOCK_RECONCILE_COUNTER);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		assertThat(outcome.getMessage()).contains("DEFAULT");
	}

	@Test
	void 존재하지_않는_발급건이면_예외를_던지지_않고_FAIL_Outcome을_반환한다() {
		LocalDateTime base = LocalDateTime.now();
		given(couponHistoryRepository.findAllByCouponEventIdOrderByIssueAndTime(EVENT_ID)).willReturn(List.of(
				history(1L, 30L, null, CouponIssueStatus.ISSUED, base),
				history(2L, 30L, null, CouponIssueStatus.ISSUED, base.plusSeconds(1))));
		given(couponIssueRepository.findByIdForUpdate(30L)).willReturn(Optional.empty());

		RecoveryOutcome outcome = recoverSingle(failResultFor(EVENT_ID), RecoveryAction.DEFAULT);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		assertThat(outcome.getMessage()).contains("issueId=30");
	}

	@Test
	void ALL_스코프_target이면_violation_내역의_issueId에서_eventId를_조회하여_복구한다() {
		VerificationResultEntity allScopeTarget = VerificationResultEntity.from(VerificationResult.fail(
				"StateMachineConsistencyCheck", TriggerType.SCHEDULED, Scope.all(LocalDateTime.now()),
				2, Map.of(), List.of(), LocalDateTime.now(), 10L));

		VerificationViolationEntity v1 = VerificationViolationEntity.builder()
				.verificationResultId(1L).targetType(ViolationTargetType.ISSUE).targetId(101L).build();
		VerificationViolationEntity v2 = VerificationViolationEntity.builder()
				.verificationResultId(1L).targetType(ViolationTargetType.ISSUE).targetId(102L).build();

		given(verificationViolationRepository.findByVerificationResultId(allScopeTarget.getId()))
				.willReturn(List.of(v1, v2));

		CouponIssue issue1 = CouponIssue.builder().id(101L).couponEvent(CouponEvent.builder().id(EVENT_ID).build()).build();
		CouponIssue issue2 = CouponIssue.builder().id(102L).couponEvent(CouponEvent.builder().id(2L).build()).build();

		given(couponIssueRepository.findAllById(List.of(101L, 102L))).willReturn(List.of(issue1, issue2));

		given(couponHistoryRepository.findAllByCouponEventIdOrderByIssueAndTime(EVENT_ID)).willReturn(List.of());
		given(couponHistoryRepository.findAllByCouponEventIdOrderByIssueAndTime(2L)).willReturn(List.of());

		List<RecoveryOutcome> outcomes = policy.recover(allScopeTarget, RecoveryAction.RESTORE_STATE_MACHINE);

		assertThat(outcomes).hasSize(2);
		assertThat(outcomes).allMatch(outcome -> outcome.getStatus() == RecoveryResultStatus.SUCCESS);
		assertThat(outcomes).extracting(outcome -> outcome.getRevalidationScope().getEventId())
				.containsExactlyInAnyOrder(1L, 2L);
	}

	@Test
	void ALL_스코프_target인데_위반내역이_없으면_예외를_던진다() {
		VerificationResultEntity allScopeTarget = VerificationResultEntity.from(VerificationResult.fail(
				"StateMachineConsistencyCheck", TriggerType.SCHEDULED, Scope.all(LocalDateTime.now()),
				0, Map.of(), List.of(), LocalDateTime.now(), 10L));
		
		given(verificationViolationRepository.findByVerificationResultId(allScopeTarget.getId()))
				.willReturn(List.of());

		assertThatThrownBy(() -> policy.recover(allScopeTarget, RecoveryAction.RESTORE_STATE_MACHINE))
				.isInstanceOf(ConsistencyCheckException.class)
				.extracting(ex -> ((ConsistencyCheckException) ex).getErrorCode())
				.isEqualTo(ErrorCode.RECOVERY_TARGET_EVENTS_NOT_FOUND);
	}
}
