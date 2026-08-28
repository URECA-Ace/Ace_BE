package com.ace.consistency.batch;

import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResultPersister;
import com.ace.consistency.repository.VerificationViolationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.step.StepExecution;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ConsistencyStepCompletionListenerTest {

	@Test
	void 결과_저장_예외를_Step_FAILED로_전파한다() {
		VerificationResultPersister persister = mock(VerificationResultPersister.class);
		given(persister.saveStepResult(any(), any(), any(), any(Boolean.class)))
				.willThrow(new IllegalStateException("count mismatch"));
		CheckResultAccumulatorWriter writer = new CheckResultAccumulatorWriter(mock(VerificationViolationRepository.class));
		ConsistencyStepCompletionListener listener = new ConsistencyStepCompletionListener(
				passingCheck(), writer, Scope.all(LocalDateTime.now()), TriggerType.SCHEDULED, persister);
		StepExecution step = stepExecution();

		ExitStatus exitStatus = listener.afterStep(step);

		assertThat(step.getStatus()).isEqualTo(BatchStatus.FAILED);
		assertThat(step.getFailureExceptions()).hasSize(1);
		assertThat(exitStatus.getExitCode()).isEqualTo(ExitStatus.FAILED.getExitCode());
		assertThat(exitStatus.getExitDescription()).contains("count mismatch");
	}

	@Test
	void STOPPED_Step은_ERROR로_저장하고_임시_violation을_연결하지_않는다() {
		VerificationResultPersister persister = mock(VerificationResultPersister.class);
		CheckResultAccumulatorWriter writer = new CheckResultAccumulatorWriter(mock(VerificationViolationRepository.class));
		ConsistencyStepCompletionListener listener = new ConsistencyStepCompletionListener(
				passingCheck(), writer, Scope.all(LocalDateTime.now()), TriggerType.SCHEDULED, persister);
		StepExecution step = stepExecution();
		step.setStatus(BatchStatus.STOPPED);

		ExitStatus exitStatus = listener.afterStep(step);

		assertThat(exitStatus).isEqualTo(ExitStatus.COMPLETED);
		verify(persister).saveStepResult(any(), eq(5L), eq("TestStep"), eq(true));
	}

	private StepExecution stepExecution() {
		JobInstance instance = new JobInstance(5L, "job");
		JobExecution jobExecution = new JobExecution(7L, instance, null);
		StepExecution step = new StepExecution(9L, "TestStep", jobExecution);
		step.setStartTime(LocalDateTime.now().minusSeconds(1));
		step.setStatus(BatchStatus.COMPLETED);
		step.setExitStatus(ExitStatus.COMPLETED);
		return step;
	}

	private ConsistencyCheck passingCheck() {
		return new ConsistencyCheck() {
			@Override public Set<Scope.ScopeType> supportedScopeTypes() { return Set.of(Scope.ScopeType.ALL); }
			@Override public CheckOutcome check(Scope scope) { return CheckOutcome.pass(); }
		};
	}
}
