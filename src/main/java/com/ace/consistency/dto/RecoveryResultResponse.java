package com.ace.consistency.dto;

import java.time.LocalDateTime;
import java.util.Map;

import com.ace.consistency.recovery.RecoveryResult;
import com.ace.consistency.recovery.RecoveryResultStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class RecoveryResultResponse {

	private final Long id;
	private final Long verificationResultId;
	private final Map<String, Object> detail;
	private final String message;
	private final RecoveryResultStatus status;
	private final LocalDateTime createdAt;

	public static RecoveryResultResponse from(RecoveryResult result) {
		return RecoveryResultResponse.builder()
				.id(result.getId())
				.verificationResultId(result.getVerificationResultId())
				.detail(result.getDetail())
				.message(result.getMessage())
				.status(result.getStatus())
				.createdAt(result.getCreatedAt())
				.build();
	}
}
