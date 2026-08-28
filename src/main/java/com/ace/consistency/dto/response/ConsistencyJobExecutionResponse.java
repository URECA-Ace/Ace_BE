package com.ace.consistency.dto.response;

import java.time.LocalDateTime;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsistencyJobExecutionResponse(
		Long jobExecutionId,
		BatchStatus status,
		LocalDateTime createTime,
		LocalDateTime startTime,
		LocalDateTime endTime,
		LocalDateTime lastUpdated,
		String exitCode,
		String errorMessage) {

	public static ConsistencyJobExecutionResponse from(JobExecution execution) {
		String description = execution.getExitStatus().getExitDescription();
		return new ConsistencyJobExecutionResponse(
				execution.getId(), execution.getStatus(), execution.getCreateTime(), execution.getStartTime(),
				execution.getEndTime(), execution.getLastUpdated(), execution.getExitStatus().getExitCode(),
				description == null || description.isBlank() ? null : description);
	}
}
