package com.ace.consistency.recovery.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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
class IssueHistoryTimeSyncConsistencyRecoveryPolicyTest {

	private static final Long EVENT_ID = 1L;

	@Mock
	private CouponIssueRepository couponIssueRepository;

	@Mock
	private CouponHistoryRepository couponHistoryRepository;

	@Mock
	private CouponEventRepository couponEventRepository;

	@Mock
	private VerificationViolationRepository verificationViolationRepository;

	private IssueHistoryTimeSyncConsistencyRecoveryPolicy policy;
	private EventLogRecoveryExecutor executor;

	@BeforeEach
	void setUp() {
		executor = new EventLogRecoveryExecutor(couponIssueRepository, couponHistoryRepository, couponEventRepository);
		policy = new IssueHistoryTimeSyncConsistencyRecoveryPolicy(couponIssueRepository, verificationViolationRepository, executor);
		// syncTimestamps()가 맨 먼저 거는 이벤트 락. DEFAULT_액션이_아닌 케이스처럼
		// 이 락까지 도달하지 않는 테스트도 있으므로 lenient로 미사용 스터빙 오류를 피한다.
		lenient().when(couponEventRepository.findByIdForUpdate(EVENT_ID)).thenReturn(Optional.of(mock(CouponEvent.class)));
		lenient().when(couponEventRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(mock(CouponEvent.class)));
	}

	private VerificationResultEntity failResultFor(Long eventId) {
		return VerificationResultEntity.from(VerificationResult.fail(
				"IssueHistoryTimeSyncConsistencyCheck", TriggerType.ON_DEMAND, Scope.ofEvent(eventId),
				1, Map.of(), List.of(), LocalDateTime.now(), 10L));
	}

	private CouponHistory history(CouponIssueStatus from, CouponIssueStatus to, LocalDateTime occurredAt) {
		return CouponHistory.builder().fromStatus(from).toStatus(to).occurredAt(occurredAt).recordedAt(occurredAt).build();
	}

	private RecoveryOutcome recoverSingle(VerificationResultEntity target, RecoveryAction action) {
		List<RecoveryOutcome> outcomes = policy.recover(target, action);
		assertThat(outcomes).hasSize(1);
		return outcomes.get(0);
	}

	@Test
	void USED_상태에서_history가_더_최신이면_used_at을_patch한다() {
		LocalDateTime base = LocalDateTime.now().withNano(0);
		LocalDateTime staleUsedAt = base;
		LocalDateTime realHistoryTime = base.plusMinutes(10);

		CouponIssue candidate = CouponIssue.builder().id(40L).status(CouponIssueStatus.USED).usedAt(staleUsedAt).build();
		given(couponIssueRepository.findByCouponEvent_IdAndStatusIn(eq(EVENT_ID), anyList())).willReturn(List.of(candidate));
		given(couponHistoryRepository.findAllByCouponIssue_IdOrderByOccurredAtAsc(40L)).willReturn(List.of(
				history(null, CouponIssueStatus.ISSUED, base.minusMinutes(20)),
				history(CouponIssueStatus.ISSUED, CouponIssueStatus.USED, realHistoryTime)));
		CouponIssue lockedIssue = CouponIssue.builder().id(40L).status(CouponIssueStatus.USED).usedAt(staleUsedAt).build();
		given(couponIssueRepository.findByIdForUpdate(40L)).willReturn(Optional.of(lockedIssue));

		RecoveryOutcome outcome = recoverSingle(failResultFor(EVENT_ID), RecoveryAction.DEFAULT);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		assertThat(outcome.getDetail()).containsEntry("patchedIssueIds", List.of(40L));
		assertThat(lockedIssue.getUsedAt()).isCloseTo(realHistoryTime, within(1, ChronoUnit.SECONDS));
	}

	@Test
	void coupon_issue가_이미_더_최신이면_아무것도_건드리지_않는다() {
		LocalDateTime base = LocalDateTime.now().withNano(0);
		LocalDateTime realUsedAt = base.plusMinutes(10);
		LocalDateTime staleHistoryTime = base;

		CouponIssue candidate = CouponIssue.builder().id(41L).status(CouponIssueStatus.USED).usedAt(realUsedAt).build();
		given(couponIssueRepository.findByCouponEvent_IdAndStatusIn(eq(EVENT_ID), anyList())).willReturn(List.of(candidate));
		given(couponHistoryRepository.findAllByCouponIssue_IdOrderByOccurredAtAsc(41L)).willReturn(List.of(
				history(null, CouponIssueStatus.ISSUED, base.minusMinutes(20)),
				history(CouponIssueStatus.ISSUED, CouponIssueStatus.USED, staleHistoryTime)));

		RecoveryOutcome outcome = recoverSingle(failResultFor(EVENT_ID), RecoveryAction.DEFAULT);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		assertThat(outcome.getDetail()).containsEntry("patchedIssueIds", List.of());
		assertThat(candidate.getUsedAt()).isEqualTo(realUsedAt); // 손대지 않음
	}

	@Test
	void 허용_오차_1초_이내_차이는_조치하지_않는다() {
		LocalDateTime base = LocalDateTime.now().withNano(0);
		CouponIssue candidate = CouponIssue.builder().id(42L).status(CouponIssueStatus.USED).usedAt(base).build();
		given(couponIssueRepository.findByCouponEvent_IdAndStatusIn(eq(EVENT_ID), anyList())).willReturn(List.of(candidate));
		given(couponHistoryRepository.findAllByCouponIssue_IdOrderByOccurredAtAsc(42L)).willReturn(List.of(
				history(null, CouponIssueStatus.ISSUED, base.minusMinutes(5)),
				history(CouponIssueStatus.ISSUED, CouponIssueStatus.USED, base.plusNanos(500_000_000))));

		RecoveryOutcome outcome = recoverSingle(failResultFor(EVENT_ID), RecoveryAction.DEFAULT);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		assertThat(outcome.getDetail()).containsEntry("patchedIssueIds", List.of());
	}

	@Test
	void coupon_issue의_기준_시각_자체가_비어있으면_history_값으로_채운다() {
		LocalDateTime base = LocalDateTime.now().withNano(0);
		CouponIssue candidate = CouponIssue.builder().id(43L).status(CouponIssueStatus.USED).usedAt(null).build();
		given(couponIssueRepository.findByCouponEvent_IdAndStatusIn(eq(EVENT_ID), anyList())).willReturn(List.of(candidate));
		given(couponHistoryRepository.findAllByCouponIssue_IdOrderByOccurredAtAsc(43L)).willReturn(List.of(
				history(null, CouponIssueStatus.ISSUED, base.minusMinutes(20)),
				history(CouponIssueStatus.ISSUED, CouponIssueStatus.USED, base)));
		CouponIssue lockedIssue = CouponIssue.builder().id(43L).status(CouponIssueStatus.USED).usedAt(null).build();
		given(couponIssueRepository.findByIdForUpdate(43L)).willReturn(Optional.of(lockedIssue));

		RecoveryOutcome outcome = recoverSingle(failResultFor(EVENT_ID), RecoveryAction.DEFAULT);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		assertThat(lockedIssue.getUsedAt()).isCloseTo(base, within(1, ChronoUnit.SECONDS));
	}

	@Test
	void 최초_발급이_아닌_재진입_케이스는_회수하지_않고_FAIL을_반환한다() {
		LocalDateTime base = LocalDateTime.now().withNano(0);
		CouponIssue candidate = CouponIssue.builder().id(44L).status(CouponIssueStatus.ISSUED).issuedAt(base).build();
		given(couponIssueRepository.findByCouponEvent_IdAndStatusIn(eq(EVENT_ID), anyList())).willReturn(List.of(candidate));
		given(couponHistoryRepository.findAllByCouponIssue_IdOrderByOccurredAtAsc(44L)).willReturn(List.of(
				history(null, CouponIssueStatus.ISSUED, base.minusDays(1)),
				history(CouponIssueStatus.USED, CouponIssueStatus.ISSUED, base))); // 사용 후 복원된 재진입 케이스

		RecoveryOutcome outcome = recoverSingle(failResultFor(EVENT_ID), RecoveryAction.DEFAULT);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		assertThat(outcome.getDetail()).containsEntry("notEligibleIssueIds", List.of(44L));
	}

	@Test
	void 이력이_없는_발급건은_건너뛴다() {
		CouponIssue candidate = CouponIssue.builder().id(45L).status(CouponIssueStatus.ISSUED).build();
		given(couponIssueRepository.findByCouponEvent_IdAndStatusIn(eq(EVENT_ID), anyList())).willReturn(List.of(candidate));
		given(couponHistoryRepository.findAllByCouponIssue_IdOrderByOccurredAtAsc(45L)).willReturn(List.of());

		RecoveryOutcome outcome = recoverSingle(failResultFor(EVENT_ID), RecoveryAction.DEFAULT);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		assertThat(outcome.getMessage()).contains("필요한 발급 건이 없습니다");
	}

	@Test
	void DEFAULT_액션이_아니면_FAIL_Outcome을_반환하고_아무것도_조회하지_않는다() {
		RecoveryOutcome outcome = recoverSingle(failResultFor(EVENT_ID), RecoveryAction.STOCK_RECONCILE_COUNTER);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		assertThat(outcome.getMessage()).contains("DEFAULT");
	}

	@Test
	void 동기화_대상_후보가_없으면_아무것도_하지_않고_SUCCESS를_반환한다() {
		given(couponIssueRepository.findByCouponEvent_IdAndStatusIn(eq(EVENT_ID), anyList())).willReturn(List.of());

		RecoveryOutcome outcome = recoverSingle(failResultFor(EVENT_ID), RecoveryAction.DEFAULT);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
	}

	@Test
	void ALL_스코프_target이면_violation_내역의_issueId에서_eventId를_조회하여_복구한다() {
		VerificationResultEntity allScopeTarget = VerificationResultEntity.from(VerificationResult.fail(
				"IssueHistoryTimeSyncConsistencyCheck", TriggerType.SCHEDULED, Scope.all(LocalDateTime.now()),
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

		given(couponIssueRepository.findByCouponEvent_IdAndStatusIn(eq(EVENT_ID), anyList())).willReturn(List.of());
		given(couponIssueRepository.findByCouponEvent_IdAndStatusIn(eq(2L), anyList())).willReturn(List.of());

		List<RecoveryOutcome> outcomes = policy.recover(allScopeTarget, RecoveryAction.DEFAULT);

		assertThat(outcomes).hasSize(2);
		assertThat(outcomes).allMatch(outcome -> outcome.getStatus() == RecoveryResultStatus.SUCCESS);
		assertThat(outcomes.get(0).getRevalidationScope().getEventId()).isEqualTo(EVENT_ID);
		assertThat(outcomes.get(1).getRevalidationScope().getEventId()).isEqualTo(2L);
	}

	@Test
	void ALL_스코프_target인데_violation이_없으면_예외를_던진다() {
		VerificationResultEntity allScopeTarget = VerificationResultEntity.from(VerificationResult.fail(
				"IssueHistoryTimeSyncConsistencyCheck", TriggerType.SCHEDULED, Scope.all(LocalDateTime.now()),
				0, Map.of(), List.of(), LocalDateTime.now(), 10L));

		given(verificationViolationRepository.findByVerificationResultId(allScopeTarget.getId())).willReturn(List.of());

		assertThatThrownBy(() -> policy.recover(allScopeTarget, RecoveryAction.DEFAULT))
				.isInstanceOf(ConsistencyCheckException.class)
				.extracting(ex -> ((ConsistencyCheckException) ex).getErrorCode())
				.isEqualTo(ErrorCode.RECOVERY_TARGET_EVENTS_NOT_FOUND);
	}
}
