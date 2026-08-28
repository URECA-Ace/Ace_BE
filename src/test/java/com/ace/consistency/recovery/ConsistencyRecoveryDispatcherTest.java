package com.ace.consistency.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.ConsistencyVerificationRunner;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResult;
import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.recovery.enums.RecoveryAction;
import com.ace.consistency.recovery.enums.RecoveryResultStatus;
import com.ace.consistency.recovery.policy.ConsistencyRecoveryPolicy;
import com.ace.consistency.recovery.service.RecoveryResultRecorder;
import com.ace.consistency.repository.VerificationResultRepository;

@ExtendWith(MockitoExtension.class)
class ConsistencyRecoveryDispatcherTest {

	private static final String CHECK_NAME = "StockConsistencyCheck";

	@Mock
	private VerificationResultRepository verificationResultRepository;

	@Mock
	private RecoveryResultRecorder recoveryResultRecorder;

	@Mock
	private ConsistencyVerificationRunner verificationRunner;

	private ConsistencyRecoveryPolicy policy;
	private ConsistencyCheck check;
	private ConsistencyRecoveryDispatcher dispatcher;

	@BeforeEach
	void setUp() {
		policy = mock(ConsistencyRecoveryPolicy.class);
		given(policy.checkName()).willReturn(CHECK_NAME);

		check = mock(ConsistencyCheck.class);
		given(check.getName()).willReturn(CHECK_NAME);

		dispatcher = new ConsistencyRecoveryDispatcher(
				verificationResultRepository, recoveryResultRecorder, verificationRunner,
				List.of(policy), List.of(check));
		dispatcher.index();
	}

	private void stubSaveReturnsInput() {
		given(recoveryResultRecorder.record(any(Long.class), any(RecoveryOutcome.class)))
				.willAnswer(invocation -> RecoveryResult.from(
						invocation.getArgument(0), invocation.getArgument(1), LocalDateTime.now()));
	}

	private VerificationResultEntity failResult() {
		return VerificationResultEntity.from(VerificationResult.fail(
				CHECK_NAME, TriggerType.ON_DEMAND, Scope.ofEvent(1L),
				1, Map.of("eventId", 1L), List.of(), LocalDateTime.now(), 10L));
	}

	@Test
	void FAIL_결과에_대해_정책을_실행하고_이력을_저장한_뒤_재검증해서_RECOVERED로_갱신한다() {
		stubSaveReturnsInput();
		VerificationResultEntity target = failResult();
		given(verificationResultRepository.findById(1L)).willReturn(Optional.of(target));

		Scope revalidationScope = Scope.ofEvent(1L);
		given(policy.recover(target, RecoveryAction.DEFAULT))
				.willReturn(List.of(RecoveryOutcome.success(revalidationScope, Map.of("issue_id", 4), "복구완료")));
		given(verificationRunner.run(List.of(check), revalidationScope, TriggerType.RECOVERY_REVALIDATION))
				.willReturn(List.of(VerificationResult.pass(
						CHECK_NAME, TriggerType.RECOVERY_REVALIDATION, revalidationScope, LocalDateTime.now(), 10L)));

		List<RecoveryResult> results = dispatcher.recover(1L, RecoveryAction.DEFAULT);

		assertThat(results).hasSize(1);
		RecoveryResult result = results.get(0);
		assertThat(result.getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		assertThat(result.getVerificationResultId()).isEqualTo(1L);
		assertThat(result.getDetail()).containsEntry("issue_id", 4);
		assertThat(result.getMessage()).isEqualTo("복구완료");
		assertThat(target.getRecoveryStatus()).isEqualTo(VerificationResultEntity.RecoveryStatus.RECOVERED);
	}

	@Test
	void 복구_자체가_실패하면_재검증_없이_바로_RECOVERY_FAILED로_갱신한다() {
		stubSaveReturnsInput();
		VerificationResultEntity target = failResult();
		given(verificationResultRepository.findById(1L)).willReturn(Optional.of(target));

		Scope revalidationScope = Scope.ofEvent(1L);
		given(policy.recover(target, RecoveryAction.DEFAULT))
				.willReturn(List.of(RecoveryOutcome.failure(revalidationScope, Map.of(), "복구 실패")));

		dispatcher.recover(1L, RecoveryAction.DEFAULT);

		assertThat(target.getRecoveryStatus()).isEqualTo(VerificationResultEntity.RecoveryStatus.RECOVERY_FAILED);
		verify(verificationRunner, never()).run(any(), any(), any());
	}

	@Test
	void 복구_자체가_실패했다면_재검증이_PASS를_반환하더라도_RECOVERED로_처리되지_않는다() {
		stubSaveReturnsInput();
		VerificationResultEntity target = failResult();
		given(verificationResultRepository.findById(1L)).willReturn(Optional.of(target));

		Scope revalidationScope = Scope.ofEvent(1L);
		given(policy.recover(target, RecoveryAction.DEFAULT))
				.willReturn(List.of(RecoveryOutcome.failure(revalidationScope, Map.of(), "복구 실패")));
		// 재검증이 호출된다면 PASS를 반환하도록 스텁해두지만, FAIL outcome에서는 애초에 호출되지 않아야 한다.
		lenient().when(verificationRunner.run(List.of(check), revalidationScope, TriggerType.RECOVERY_REVALIDATION))
				.thenReturn(List.of(VerificationResult.pass(
						CHECK_NAME, TriggerType.RECOVERY_REVALIDATION, revalidationScope, LocalDateTime.now(), 10L)));

		List<RecoveryResult> results = dispatcher.recover(1L, RecoveryAction.DEFAULT);

		assertThat(results.get(0).getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		assertThat(target.getRecoveryStatus()).isEqualTo(VerificationResultEntity.RecoveryStatus.RECOVERY_FAILED);
		verify(verificationRunner, never()).run(any(), any(), any());
	}

	@Test
	void PASS_상태인_결과는_복구할_수_없다() {
		VerificationResultEntity passResult = VerificationResultEntity.from(VerificationResult.pass(
				CHECK_NAME, TriggerType.ON_DEMAND, Scope.ofEvent(1L), LocalDateTime.now(), 10L));
		given(verificationResultRepository.findById(2L)).willReturn(Optional.of(passResult));

		assertThatThrownBy(() -> dispatcher.recover(2L, RecoveryAction.DEFAULT))
				.isInstanceOf(ConsistencyCheckException.class)
				.extracting(ex -> ((ConsistencyCheckException) ex).getErrorCode())
				.isEqualTo(ErrorCode.RECOVERY_NOT_APPLICABLE);
	}

	@Test
	void 정책이_여러_건의_RecoveryOutcome을_반환하면_각각_이력을_저장하고_재검증해서_모두_통과하면_RECOVERED로_갱신한다() {
		stubSaveReturnsInput();
		VerificationResultEntity target = failResult();
		given(verificationResultRepository.findById(1L)).willReturn(Optional.of(target));

		Scope scope1 = Scope.ofEvent(1L);
		Scope scope2 = Scope.ofEvent(2L);
		given(policy.recover(target, RecoveryAction.DEFAULT)).willReturn(List.of(
				RecoveryOutcome.success(scope1, Map.of(), "이벤트 1 복구완료"),
				RecoveryOutcome.success(scope2, Map.of(), "이벤트 2 복구완료")));
		given(verificationRunner.run(List.of(check), scope1, TriggerType.RECOVERY_REVALIDATION))
				.willReturn(List.of(VerificationResult.pass(
						CHECK_NAME, TriggerType.RECOVERY_REVALIDATION, scope1, LocalDateTime.now(), 10L)));
		given(verificationRunner.run(List.of(check), scope2, TriggerType.RECOVERY_REVALIDATION))
				.willReturn(List.of(VerificationResult.pass(
						CHECK_NAME, TriggerType.RECOVERY_REVALIDATION, scope2, LocalDateTime.now(), 10L)));

		List<RecoveryResult> results = dispatcher.recover(1L, RecoveryAction.DEFAULT);

		assertThat(results).hasSize(2);
		assertThat(target.getRecoveryStatus()).isEqualTo(VerificationResultEntity.RecoveryStatus.RECOVERED);
	}

	@Test
	void 정책이_반환한_여러_건_중_일부만_실패해도_전체가_RECOVERY_FAILED로_갱신된다() {
		stubSaveReturnsInput();
		VerificationResultEntity target = failResult();
		given(verificationResultRepository.findById(1L)).willReturn(Optional.of(target));

		Scope scope1 = Scope.ofEvent(1L);
		Scope scope2 = Scope.ofEvent(2L);
		given(policy.recover(target, RecoveryAction.DEFAULT)).willReturn(List.of(
				RecoveryOutcome.success(scope1, Map.of(), "이벤트 1 복구완료"),
				RecoveryOutcome.failure(scope2, Map.of(), "이벤트 2 복구 실패")));
		given(verificationRunner.run(List.of(check), scope1, TriggerType.RECOVERY_REVALIDATION))
				.willReturn(List.of(VerificationResult.pass(
						CHECK_NAME, TriggerType.RECOVERY_REVALIDATION, scope1, LocalDateTime.now(), 10L)));

		List<RecoveryResult> results = dispatcher.recover(1L, RecoveryAction.DEFAULT);

		assertThat(results).hasSize(2);
		assertThat(target.getRecoveryStatus()).isEqualTo(VerificationResultEntity.RecoveryStatus.RECOVERY_FAILED);
	}

	@Test
	void 정책이_예외를_던지면_그대로_전파된다() {
		VerificationResultEntity target = failResult();
		given(verificationResultRepository.findById(1L)).willReturn(Optional.of(target));

		given(policy.recover(target, RecoveryAction.DEFAULT))
				.willThrow(new ConsistencyCheckException(ErrorCode.RECOVERY_TARGET_EVENTS_NOT_FOUND));

		assertThatThrownBy(() -> dispatcher.recover(1L, RecoveryAction.DEFAULT))
				.isInstanceOf(ConsistencyCheckException.class)
				.extracting(ex -> ((ConsistencyCheckException) ex).getErrorCode())
				.isEqualTo(ErrorCode.RECOVERY_TARGET_EVENTS_NOT_FOUND);
	}

	@Test
	void 정책이_revalidationScope를_ALL로_반환하면_재검증할_수_없다() {
		stubSaveReturnsInput();
		VerificationResultEntity target = failResult();
		given(verificationResultRepository.findById(1L)).willReturn(Optional.of(target));

		Scope allScope = Scope.all(LocalDateTime.now());
		given(policy.recover(target, RecoveryAction.DEFAULT))
				.willReturn(List.of(RecoveryOutcome.success(allScope, Map.of(), "복구완료")));

		assertThatThrownBy(() -> dispatcher.recover(1L, RecoveryAction.DEFAULT))
				.isInstanceOf(ConsistencyCheckException.class)
				.extracting(ex -> ((ConsistencyCheckException) ex).getErrorCode())
				.isEqualTo(ErrorCode.RECOVERY_NOT_SUPPORTED_FOR_ALL_SCOPE);
	}

	@Test
	void 존재하지_않는_결과ID면_예외를_던진다() {
		given(verificationResultRepository.findById(99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> dispatcher.recover(99L, RecoveryAction.DEFAULT))
				.isInstanceOf(ConsistencyCheckException.class)
				.extracting(ex -> ((ConsistencyCheckException) ex).getErrorCode())
				.isEqualTo(ErrorCode.VERIFICATION_RESULT_NOT_FOUND);
	}

	@Test
	void checkName에_해당하는_복구_정책이_없으면_예외를_던진다() {
		ConsistencyRecoveryDispatcher noPolicyDispatcher = new ConsistencyRecoveryDispatcher(
				verificationResultRepository, recoveryResultRecorder, verificationRunner, List.of(), List.of(check));
		noPolicyDispatcher.index();

		VerificationResultEntity target = failResult();
		given(verificationResultRepository.findById(1L)).willReturn(Optional.of(target));

		assertThatThrownBy(() -> noPolicyDispatcher.recover(1L, RecoveryAction.DEFAULT))
				.isInstanceOf(ConsistencyCheckException.class)
				.extracting(ex -> ((ConsistencyCheckException) ex).getErrorCode())
				.isEqualTo(ErrorCode.RECOVERY_POLICY_NOT_FOUND);
	}

	@Test
	void 복구_정책이_있는_체크는_해당_정책의_availableActions를_그대로_반환한다() {
		VerificationResultEntity target = failResult();
		given(verificationResultRepository.findById(1L)).willReturn(Optional.of(target));
		given(policy.availableActions()).willReturn(List.of(RecoveryAction.DEFAULT));

		List<RecoveryAction> actions = dispatcher.availableActions(1L);

		assertThat(actions).containsExactly(RecoveryAction.DEFAULT);
	}

	@Test
	void 복구_정책이_없는_체크는_예외_대신_빈_목록을_반환한다() {
		ConsistencyRecoveryDispatcher noPolicyDispatcher = new ConsistencyRecoveryDispatcher(
				verificationResultRepository, recoveryResultRecorder, verificationRunner, List.of(), List.of(check));
		noPolicyDispatcher.index();

		VerificationResultEntity target = failResult();
		given(verificationResultRepository.findById(1L)).willReturn(Optional.of(target));

		assertThat(noPolicyDispatcher.availableActions(1L)).isEmpty();
	}
}
