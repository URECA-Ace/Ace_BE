package com.ace.consistency.batch;

import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.ViolationTargetType;
import com.ace.consistency.entity.VerificationViolationEntity;
import com.ace.consistency.repository.VerificationViolationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CheckResultAccumulatorWriterTest {

	@Mock VerificationViolationRepository violationRepository;

	@Test
	void 청크의_위반을_JobInstance와_StepName으로_즉시_저장한다() throws Exception {
		CheckResultAccumulatorWriter writer = writerWithOwner(11L, "StockStep");
		writer.open(new ExecutionContext());
		ConsistencyCheck.Violation violation = new ConsistencyCheck.Violation(
				ViolationTargetType.EVENT, 101L, Map.of("reason", "stock mismatch"));
		ConsistencyCheck.CheckOutcome outcome = ConsistencyCheck.CheckOutcome.fail(
				1, Map.of("violationCount", 1), List.of(violation));

		writer.write(new Chunk<>(List.of(outcome)));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<VerificationViolationEntity>> captor = ArgumentCaptor.forClass(List.class);
		verify(violationRepository).saveAll(captor.capture());
		VerificationViolationEntity saved = captor.getValue().getFirst();
		assertThat(saved.getBatchJobInstanceId()).isEqualTo(11L);
		assertThat(saved.getBatchStepName()).isEqualTo("StockStep");
		assertThat(saved.getVerificationResultId()).isNull();
		assertThat(saved.getTargetId()).isEqualTo(101L);
		assertThat(writer.getViolationCount()).isEqualTo(1);
	}

	@Test
	void 재시작하면_ExecutionContext의_누적_건수부터_계속한다() throws Exception {
		CheckResultAccumulatorWriter writer = writerWithOwner(11L, "StockStep");
		ExecutionContext context = new ExecutionContext();
		context.putInt("checkResultAccumulatorWriter.violationCount", 3);
		writer.open(context);
		writer.write(new Chunk<>(List.of(ConsistencyCheck.CheckOutcome.fail(
				2, Map.of(), List.of()))));
		writer.update(context);

		assertThat(writer.getViolationCount()).isEqualTo(5);
		assertThat(context.getInt("checkResultAccumulatorWriter.violationCount")).isEqualTo(5);
	}

	private CheckResultAccumulatorWriter writerWithOwner(Long jobInstanceId, String stepName) {
		CheckResultAccumulatorWriter writer = new CheckResultAccumulatorWriter(violationRepository);
		StepExecution stepExecution = org.mockito.Mockito.mock(StepExecution.class);
		JobExecution jobExecution = org.mockito.Mockito.mock(JobExecution.class);
		JobInstance jobInstance = org.mockito.Mockito.mock(JobInstance.class);
		given(stepExecution.getJobExecution()).willReturn(jobExecution);
		given(jobExecution.getJobInstance()).willReturn(jobInstance);
		given(jobInstance.getInstanceId()).willReturn(jobInstanceId);
		given(stepExecution.getStepName()).willReturn(stepName);
		writer.beforeStep(stepExecution);
		return writer;
	}
}
