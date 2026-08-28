package com.ace.consistency.recovery.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import com.ace.consistency.recovery.enums.RecoveryResultStatus;
import com.ace.consistency.repository.VerificationViolationRepository;

@ExtendWith(MockitoExtension.class)
class StateMachineConsistencyRecoveryPolicyTest {

	private static final Long EVENT_ID = 1L;

	@Mock
	private EventLogRecoveryExecutor eventRecoveryExecutor;

	@Mock
	private VerificationViolationRepository violationRepository;

	private StateMachineConsistencyRecoveryPolicy policy;

	@BeforeEach
	void setUp() {
		policy = new StateMachineConsistencyRecoveryPolicy(eventRecoveryExecutor, violationRepository);
	}

	private VerificationViolationEntity violationFor(Long targetId) {
		return VerificationViolationEntity.forResult(10L, new Violation(ViolationTargetType.ISSUE, targetId, Map.of()));
	}

	private VerificationResultEntity failResultFor(Long eventId) {
		return VerificationResultEntity.from(VerificationResult.fail(
				"StateMachineConsistencyCheck", TriggerType.ON_DEMAND, Scope.ofEvent(eventId),
				1, Map.of(), List.of(), LocalDateTime.now(), 10L));
	}

	@Test
	void EVENT_스코프_target이면_해당_eventId로_executor를_호출한다() {
		VerificationResultEntity target = failResultFor(EVENT_ID);
		given(eventRecoveryExecutor.restoreStateMachine(EVENT_ID))
				.willReturn(RecoveryOutcome.success(Scope.ofEvent(EVENT_ID), Map.of(), "성공"));

		List<RecoveryOutcome> outcomes = policy.recover(target, RecoveryAction.RESTORE_STATE_MACHINE);

		assertThat(outcomes).hasSize(1);
		assertThat(outcomes.get(0).getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		verify(eventRecoveryExecutor).restoreStateMachine(EVENT_ID);
	}

	@Test
	void ALL_스코프_target이면_verification_violation에_있는_targetId마다_executor를_호출한다() {
		VerificationResultEntity target = VerificationResultEntity.from(VerificationResult.fail(
				"StateMachineConsistencyCheck", TriggerType.SCHEDULED, Scope.all(LocalDateTime.now()),
				2, Map.of(), List.of(), LocalDateTime.now(), 10L));
		given(violationRepository.findByVerificationResultId(any()))
				.willReturn(List.of(violationFor(1L), violationFor(2L)));

		given(eventRecoveryExecutor.restoreStateMachine(1L))
				.willReturn(RecoveryOutcome.success(Scope.ofEvent(1L), Map.of(), "성공"));
		given(eventRecoveryExecutor.restoreStateMachine(2L))
				.willReturn(RecoveryOutcome.failure(Scope.ofEvent(2L), Map.of(), "실패"));

		List<RecoveryOutcome> outcomes = policy.recover(target, RecoveryAction.RESTORE_STATE_MACHINE);

		assertThat(outcomes).hasSize(2);
		assertThat(outcomes).extracting(RecoveryOutcome::getStatus)
				.containsExactly(RecoveryResultStatus.SUCCESS, RecoveryResultStatus.FAIL);
		verify(eventRecoveryExecutor).restoreStateMachine(1L);
		verify(eventRecoveryExecutor).restoreStateMachine(2L);
	}

	@Test
	void 지원하지_않는_액션이면_executor를_호출하지_않고_FAIL_Outcome을_반환한다() {
		VerificationResultEntity target = failResultFor(EVENT_ID);

		List<RecoveryOutcome> outcomes = policy.recover(target, RecoveryAction.DEFAULT); // DEFAULT는 이제 미지원

		assertThat(outcomes).hasSize(1);
		assertThat(outcomes.get(0).getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		assertThat(outcomes.get(0).getMessage()).contains("지원하지 않는 액션입니다");
		verifyNoInteractions(eventRecoveryExecutor);
	}

	@Test
	void ALL_스코프_target인데_verification_violation이_없으면_예외를_던진다() {
		VerificationResultEntity target = VerificationResultEntity.from(VerificationResult.fail(
				"StateMachineConsistencyCheck", TriggerType.SCHEDULED, Scope.all(LocalDateTime.now()),
				0, Map.of(), List.of(), LocalDateTime.now(), 10L));
		given(violationRepository.findByVerificationResultId(any())).willReturn(List.of());

		assertThatThrownBy(() -> policy.recover(target, RecoveryAction.RESTORE_STATE_MACHINE))
				.isInstanceOf(ConsistencyCheckException.class)
				.hasMessageContaining("복구할 위반 이벤트를 검증 결과에서 찾을 수 없습니다");
	}
}
