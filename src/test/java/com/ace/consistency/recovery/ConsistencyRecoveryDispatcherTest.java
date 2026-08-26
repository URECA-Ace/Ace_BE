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
import com.ace.consistency.recovery.repository.RecoveryResultRepository;
import com.ace.consistency.repository.VerificationResultRepository;

@ExtendWith(MockitoExtension.class)
class ConsistencyRecoveryDispatcherTest {

	private static final String CHECK_NAME = "StockConsistencyCheck";

	@Mock
	private VerificationResultRepository verificationResultRepository;

	@Mock
	private RecoveryResultRepository recoveryResultRepository;

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
				verificationResultRepository, recoveryResultRepository, verificationRunner,
				List.of(policy), List.of(check));
		dispatcher.index();
	}

	private void stubSaveReturnsInput() {
		given(recoveryResultRepository.save(any()))
				.willAnswer(invocation -> invocation.getArgument(0));
	}

	private VerificationResultEntity failResult() {
		return VerificationResultEntity.from(VerificationResult.fail(
				CHECK_NAME, TriggerType.ON_DEMAND, Scope.ofEvent(1L),
				1, Map.of("eventId", 1L), LocalDateTime.now(), 10L));
	}

	@Test
	void FAIL_결과에_대해_정책을_실행하고_이력을_저장한_뒤_재검증해서_RECOVERED로_갱신한다() {
		stubSaveReturnsInput();
		VerificationResultEntity target = failResult();
		given(verificationResultRepository.findById(1L)).willReturn(Optional.of(target));

		Scope revalidationScope = Scope.ofEvent(1L);
		given(policy.recover(target, RecoveryAction.DEFAULT, 1L))
				.willReturn(RecoveryOutcome.success(revalidationScope, Map.of("issue_id", 4), "복구완료"));
		given(verificationRunner.run(List.of(check), revalidationScope, TriggerType.RECOVERY_REVALIDATION))
				.willReturn(List.of(VerificationResult.pass(
						CHECK_NAME, TriggerType.RECOVERY_REVALIDATION, revalidationScope, LocalDateTime.now(), 10L)));

		RecoveryResult result = dispatcher.recover(1L, RecoveryAction.DEFAULT, null);

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
		given(policy.recover(target, RecoveryAction.DEFAULT, 1L))
				.willReturn(RecoveryOutcome.failure(revalidationScope, Map.of(), "복구 실패"));

		dispatcher.recover(1L, RecoveryAction.DEFAULT, null);

		assertThat(target.getRecoveryStatus()).isEqualTo(VerificationResultEntity.RecoveryStatus.RECOVERY_FAILED);
		verify(verificationRunner, never()).run(any(), any(), any());
	}

	@Test
	void 복구_자체가_실패했다면_재검증이_PASS를_반환하더라도_RECOVERED로_처리되지_않는다() {
		stubSaveReturnsInput();
		VerificationResultEntity target = failResult();
		given(verificationResultRepository.findById(1L)).willReturn(Optional.of(target));

		Scope revalidationScope = Scope.ofEvent(1L);
		given(policy.recover(target, RecoveryAction.DEFAULT, 1L))
				.willReturn(RecoveryOutcome.failure(revalidationScope, Map.of(), "복구 실패"));
		// 재검증이 호출된다면 PASS를 반환하도록 스텁해두지만, FAIL outcome에서는 애초에 호출되지 않아야 한다.
		lenient().when(verificationRunner.run(List.of(check), revalidationScope, TriggerType.RECOVERY_REVALIDATION))
				.thenReturn(List.of(VerificationResult.pass(
						CHECK_NAME, TriggerType.RECOVERY_REVALIDATION, revalidationScope, LocalDateTime.now(), 10L)));

		RecoveryResult result = dispatcher.recover(1L, RecoveryAction.DEFAULT, null);

		assertThat(result.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		assertThat(target.getRecoveryStatus()).isEqualTo(VerificationResultEntity.RecoveryStatus.RECOVERY_FAILED);
		verify(verificationRunner, never()).run(any(), any(), any());
	}

	@Test
	void PASS_상태인_결과는_복구할_수_없다() {
		VerificationResultEntity passResult = VerificationResultEntity.from(VerificationResult.pass(
				CHECK_NAME, TriggerType.ON_DEMAND, Scope.ofEvent(1L), LocalDateTime.now(), 10L));
		given(verificationResultRepository.findById(2L)).willReturn(Optional.of(passResult));

		assertThatThrownBy(() -> dispatcher.recover(2L, RecoveryAction.DEFAULT, null))
				.isInstanceOf(ConsistencyCheckException.class)
				.extracting(ex -> ((ConsistencyCheckException) ex).getErrorCode())
				.isEqualTo(ErrorCode.RECOVERY_NOT_APPLICABLE);
	}

	@Test
	void ALL_스코프_target이어도_정책이_이벤트_단위로_좁히면_복구할_수_있다() {
		stubSaveReturnsInput();
		VerificationResultEntity allScopeTarget = VerificationResultEntity.from(VerificationResult.fail(
				CHECK_NAME, TriggerType.SCHEDULED, Scope.all(List.of(1L, 2L), LocalDateTime.now()),
				2, Map.of(), LocalDateTime.now(), 10L));
		given(verificationResultRepository.findById(4L)).willReturn(Optional.of(allScopeTarget));

		Scope narrowedScope = Scope.ofEvent(1L);
		given(policy.recover(allScopeTarget, RecoveryAction.DEFAULT, 1L))
				.willReturn(RecoveryOutcome.success(narrowedScope, Map.of(), "이벤트 1건 복구완료"));
		given(verificationRunner.run(List.of(check), narrowedScope, TriggerType.RECOVERY_REVALIDATION))
				.willReturn(List.of(VerificationResult.pass(
						CHECK_NAME, TriggerType.RECOVERY_REVALIDATION, narrowedScope, LocalDateTime.now(), 10L)));

		dispatcher.recover(4L, RecoveryAction.DEFAULT, 1L);

		assertThat(allScopeTarget.getRecoveryStatus()).isEqualTo(VerificationResultEntity.RecoveryStatus.RECOVERED);
	}

	@Test
	void ALL_스코프_target인데_eventId를_지정하지_않으면_예외를_던진다() {
		VerificationResultEntity allScopeTarget = VerificationResultEntity.from(VerificationResult.fail(
				CHECK_NAME, TriggerType.SCHEDULED, Scope.all(List.of(1L, 2L), LocalDateTime.now()),
				2, Map.of(), LocalDateTime.now(), 10L));
		given(verificationResultRepository.findById(4L)).willReturn(Optional.of(allScopeTarget));

		assertThatThrownBy(() -> dispatcher.recover(4L, RecoveryAction.DEFAULT, null))
				.isInstanceOf(ConsistencyCheckException.class)
				.extracting(ex -> ((ConsistencyCheckException) ex).getErrorCode())
				.isEqualTo(ErrorCode.RECOVERY_EVENT_ID_REQUIRED);
	}

	@Test
	void 정책이_revalidationScope를_ALL로_반환하면_재검증할_수_없다() {
		stubSaveReturnsInput();
		VerificationResultEntity target = failResult();
		given(verificationResultRepository.findById(1L)).willReturn(Optional.of(target));

		Scope allScope = Scope.all(LocalDateTime.now());
		given(policy.recover(target, RecoveryAction.DEFAULT, 1L))
				.willReturn(RecoveryOutcome.success(allScope, Map.of(), "복구완료"));

		assertThatThrownBy(() -> dispatcher.recover(1L, RecoveryAction.DEFAULT, null))
				.isInstanceOf(ConsistencyCheckException.class)
				.extracting(ex -> ((ConsistencyCheckException) ex).getErrorCode())
				.isEqualTo(ErrorCode.RECOVERY_NOT_SUPPORTED_FOR_ALL_SCOPE);
	}

	@Test
	void 존재하지_않는_결과ID면_예외를_던진다() {
		given(verificationResultRepository.findById(99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> dispatcher.recover(99L, RecoveryAction.DEFAULT, null))
				.isInstanceOf(ConsistencyCheckException.class)
				.extracting(ex -> ((ConsistencyCheckException) ex).getErrorCode())
				.isEqualTo(ErrorCode.VERIFICATION_RESULT_NOT_FOUND);
	}

	@Test
	void checkName에_해당하는_복구_정책이_없으면_예외를_던진다() {
		ConsistencyRecoveryDispatcher noPolicyDispatcher = new ConsistencyRecoveryDispatcher(
				verificationResultRepository, recoveryResultRepository, verificationRunner, List.of(), List.of(check));
		noPolicyDispatcher.index();

		VerificationResultEntity target = failResult();
		given(verificationResultRepository.findById(1L)).willReturn(Optional.of(target));

		assertThatThrownBy(() -> noPolicyDispatcher.recover(1L, RecoveryAction.DEFAULT, null))
				.isInstanceOf(ConsistencyCheckException.class)
				.extracting(ex -> ((ConsistencyCheckException) ex).getErrorCode())
				.isEqualTo(ErrorCode.RECOVERY_POLICY_NOT_FOUND);
	}
}
