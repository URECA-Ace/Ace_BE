package com.ace.consistency.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResult;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsistencyVerificationResponse(
		ExecutionType executionType,
		Long jobExecutionId,
		List<ResultResponse> results) {

	public enum ExecutionType {
		SYNC,
		ASYNC
	}

	public static ConsistencyVerificationResponse sync(List<VerificationResult> results) {
		return new ConsistencyVerificationResponse(
				ExecutionType.SYNC,
				null,
				results.stream().map(ResultResponse::from).toList());
	}

	public static ConsistencyVerificationResponse async(long jobExecutionId) {
		return new ConsistencyVerificationResponse(ExecutionType.ASYNC, jobExecutionId, null);
	}

	public record ResultResponse(
			String checkName,
			TriggerType triggerType,
			Scope scope,
			VerificationResult.Status status,
			int violationCount,
			Map<String, Object> diffDetail,
			String errorMessage,
			LocalDateTime executedAt,
			long durationMillis) {

		static ResultResponse from(VerificationResult result) {
			return new ResultResponse(
					result.getCheckName(),
					result.getTriggerType(),
					result.getScope(),
					result.getStatus(),
					result.getViolationCount(),
					result.getDiffDetail(),
					result.getErrorMessage(),
					result.getExecutedAt(),
					result.getDurationMillis());
		}
	}
}
