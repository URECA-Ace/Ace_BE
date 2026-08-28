package com.ace.consistency.dto.response;

import java.time.LocalDateTime;
import java.util.Map;

import com.ace.consistency.common.ViolationTargetType;
import com.ace.consistency.entity.VerificationViolationEntity;

public record ConsistencyViolationResponse(
		Long id,
		ViolationTargetType targetType,
		Long targetId,
		Map<String, Object> detail,
		LocalDateTime createdAt) {

	public static ConsistencyViolationResponse from(VerificationViolationEntity entity) {
		return new ConsistencyViolationResponse(entity.getId(), entity.getTargetType(), entity.getTargetId(),
				entity.getDetail(), entity.getCreatedAt());
	}
}
