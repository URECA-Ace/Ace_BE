package com.ace.consistency.common;

import com.ace.consistency.batch.ConsistencyBatchJobFactory;
import com.ace.coupon.repository.CouponEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class ConsistencyVerificationRunnerRestartWindowTest {

	private JobRepository jobRepository;
	private ConsistencyVerificationRunner runner;

	@BeforeEach
	void setUp() {
		jobRepository = mock(JobRepository.class);
		runner = new ConsistencyVerificationRunner(
				mock(VerificationResultPersister.class), mock(CouponEventRepository.class),
				mock(ConsistencyBatchJobFactory.class), mock(JobOperator.class), jobRepository,
				mock(JobRegistry.class), List.of());
	}

	@Test
	void 재시작_허용_시간은_0보다_크고_cleanup_threshold보다_짧아야_한다() {
		ReflectionTestUtils.setField(runner, "restartAllowedMinutes", 30L);
		ReflectionTestUtils.setField(runner, "orphanThresholdMinutes", 30L);

		assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(runner, "validateViolationCleanupWindow"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("restartAllowedMinutes=30");

		ReflectionTestUtils.setField(runner, "restartAllowedMinutes", 25L);
		assertThatCode(() -> ReflectionTestUtils.invokeMethod(runner, "validateViolationCleanupWindow"))
				.doesNotThrowAnyException();
	}

	@Test
	void 마지막_진행_시각이_허용_시간을_지났으면_재시작을_거부한다() {
		ReflectionTestUtils.setField(runner, "restartAllowedMinutes", 25L);
		ReflectionTestUtils.setField(runner, "orphanThresholdMinutes", 30L);
		JobInstance instance = new JobInstance(1L, "job");
		JobExecution execution = new JobExecution(2L, instance, null);
		execution.setStatus(BatchStatus.FAILED);
		StepExecution step = new StepExecution(3L, "StockStep", execution);
		step.setStatus(BatchStatus.FAILED);
		step.setLastUpdated(LocalDateTime.now().minusMinutes(26));
		execution.addStepExecution(step);
		given(jobRepository.getJobExecution(2L)).willReturn(execution);

		assertThatThrownBy(() -> runner.restartRunAsync(2L))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("새로운 ALL 검증")
				.hasMessageContaining("jobExecutionId=2");
	}
}
