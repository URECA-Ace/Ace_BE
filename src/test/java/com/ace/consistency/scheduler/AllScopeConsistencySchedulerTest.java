package com.ace.consistency.scheduler;

import com.ace.consistency.batch.ConsistencyBatchJobFactory;
import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.ConsistencyVerificationRunner;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AllScopeConsistencySchedulerTest {

	private ConsistencyCheck check;
	private ConsistencyVerificationRunner runner;
	private JobRepository jobRepository;
	private AllScopeConsistencyScheduler scheduler;

	@BeforeEach
	void setUp() {
		check = mock(ConsistencyCheck.class);
		runner = mock(ConsistencyVerificationRunner.class);
		jobRepository = mock(JobRepository.class);

		scheduler = new AllScopeConsistencyScheduler(List.of(check), runner, jobRepository);
		ReflectionTestUtils.setField(scheduler, "safetyMarginSeconds", 10L);
	}

	@Test
	@DisplayName("이전 배치가 실행 중이 아니면 runAsync()로 ALL 스코프 배치를 시작한다")
	void startsBatchWhenNoJobIsRunning() {
		when(jobRepository.findRunningJobExecutions(ConsistencyBatchJobFactory.JOB_NAME)).thenReturn(Set.of());

		scheduler.run();

		ArgumentCaptor<Scope> scopeCaptor = ArgumentCaptor.forClass(Scope.class);
		verify(runner).runAsync(eq(List.of(check)), scopeCaptor.capture(), eq(TriggerType.SCHEDULED));
		assertEquals(Scope.ScopeType.ALL, scopeCaptor.getValue().getType());
	}

	@Test
	@DisplayName("이전 SCHEDULED 배치가 아직 실행 중이면 이번 틱은 건너뛴다")
	void skipsWhenScheduledJobIsAlreadyRunning() {
		JobExecution runningExecution = mock(JobExecution.class);
		JobParameters runningParams = new JobParametersBuilder().addString("triggerType", "SCHEDULED").toJobParameters();
		when(runningExecution.getJobParameters()).thenReturn(runningParams);
		when(jobRepository.findRunningJobExecutions(ConsistencyBatchJobFactory.JOB_NAME))
				.thenReturn(Set.of(runningExecution));

		scheduler.run();

		verify(runner, never()).runAsync(any(), any(), any());
	}

	@Test
	@DisplayName("실행 중인 게 ON_DEMAND(수동) 배치뿐이면 스케줄러는 막히지 않고 실행한다")
	void doesNotSkipWhenOnlyManualJobIsRunning() {
		JobExecution runningExecution = mock(JobExecution.class);
		JobParameters runningParams = new JobParametersBuilder().addString("triggerType", "ON_DEMAND").toJobParameters();
		when(runningExecution.getJobParameters()).thenReturn(runningParams);
		when(jobRepository.findRunningJobExecutions(ConsistencyBatchJobFactory.JOB_NAME))
				.thenReturn(Set.of(runningExecution));

		scheduler.run();

		verify(runner, times(1)).runAsync(eq(List.of(check)), any(), eq(TriggerType.SCHEDULED));
	}

	@Test
	@DisplayName("runAsync() 실행 중 예외가 나도 스케줄러 밖으로 전파되지 않는다")
	void doesNotPropagateRunnerException() {
		when(jobRepository.findRunningJobExecutions(ConsistencyBatchJobFactory.JOB_NAME)).thenReturn(Set.of());
		doThrow(new RuntimeException("배치 시작 실패")).when(runner).runAsync(any(), any(), any());

		assertDoesNotThrow(() -> scheduler.run());
	}
}
