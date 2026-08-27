package com.ace.consistency.dto.response;

import java.time.LocalDateTime;
import java.util.Map;

import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResult;
import com.ace.consistency.entity.VerificationResultEntity;

public record ConsistencyResultResponse(
		Long id,
		String checkName,
		TriggerType triggerType,
		Scope.ScopeType scopeType,
		Long eventId,
		LocalDateTime scopeFrom,
		LocalDateTime scopeTo,
		VerificationResult.Status status,
		int violationCount,
		Map<String, Object> diffDetail,
		String errorMessage,
		LocalDateTime executedAt,
		long durationMillis) {

	public static ConsistencyResultResponse from(VerificationResultEntity entity) {
		return new ConsistencyResultResponse(entity.getId(), entity.getCheckName(), entity.getTriggerType(),
				entity.getScopeType(), entity.getEventId(), entity.getScopeFrom(), entity.getScopeTo(),
				entity.getStatus(), entity.getViolationCount(), entity.getDiffDetail(), entity.getErrorMessage(),
				entity.getExecutedAt(), entity.getDurationMillis());
	}
}
