package com.ace.consistency.batch;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * batch_failure_log 테이블 매핑 엔티티.
 *
 * 정합성 검증 배치 Job이 COMPLETED가 아닌 상태로 끝났을 때(FAILED/STOPPED 등)
 * {@link ConsistencyJobExecutionListener}가 저장한다. checks/scope/triggerType처럼
 * 재시작에 필요한 정보는 이미 JobExecution의 ExecutionContext에 저장돼 있으므로
 * (재시작은 {@code ConsistencyVerificationRunner.restartRunAsync(jobExecutionId)}가 담당),
 * 이 엔티티는 "어떤 실행이, 어느 Step에서, 왜 실패했는지"만 조회 가능하게 남기는 역할만 한다.
 */
@Entity
@Table(name = "batch_failure_log", indexes = {
		@Index(name = "idx_bfl_job_execution_id", columnList = "jobExecutionId")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BatchFailureLogEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long jobExecutionId;

	@Column(nullable = false)
	private Long jobInstanceId;

	@Column(nullable = false, length = 20)
	private String status;

	/** 실패한 Step 이름. Step까지 못 가고 Job 자체가 실패한 경우 null. */
	@Column(length = 100)
	private String failedStepName;

	@Column(length = 500)
	private String errorMessage;

	@Column(nullable = false)
	private LocalDateTime occurredAt;

	@Builder
	private BatchFailureLogEntity(Long jobExecutionId, Long jobInstanceId, String status,
								   String failedStepName, String errorMessage, LocalDateTime occurredAt) {
		this.jobExecutionId = jobExecutionId;
		this.jobInstanceId = jobInstanceId;
		this.status = status;
		this.failedStepName = failedStepName;
		this.errorMessage = errorMessage;
		this.occurredAt = occurredAt;
	}

	/**
	 * COMPLETED가 아닌 상태로 끝난 JobExecution으로부터 Entity를 생성한다.
	 * 실패한 Step이 있으면 그 Step의 예외를, 없으면(Step 시작 전에 Job 자체가 실패한 경우)
	 * JobExecution 레벨의 예외를 사용한다.
	 */
	public static BatchFailureLogEntity from(JobExecution jobExecution) {
		StepExecution failedStep = jobExecution.getStepExecutions().stream()
				.filter(step -> step.getStatus() == BatchStatus.FAILED)
				.findFirst()
				.orElse(null);

		List<Throwable> failures = failedStep != null
				? failedStep.getFailureExceptions()
				: jobExecution.getFailureExceptions();
		String errorMessage = failures.isEmpty() ? null : describe(failures.getFirst());

		return builder()
				.jobExecutionId(jobExecution.getId())
				.jobInstanceId(jobExecution.getJobInstance().getId())
				.status(jobExecution.getStatus().name())
				.failedStepName(failedStep != null ? failedStep.getStepName() : null)
				.errorMessage(errorMessage)
				.occurredAt(LocalDateTime.now())
				.build();
	}

	private static String describe(Throwable cause) {
		return cause.getClass().getSimpleName() + ": " + cause.getMessage();
	}
}
