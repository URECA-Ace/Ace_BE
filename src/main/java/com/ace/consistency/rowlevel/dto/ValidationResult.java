package com.ace.consistency.rowlevel.dto;

import com.ace.consistency.rowlevel.domain.ValidationStatus;

import java.time.LocalDateTime;

public record ValidationResult(
		String checkId,
		String checkGroup,
		String targetId,
		ValidationStatus status,
		String expectedValue,
		String actualValue,
		LocalDateTime detectedAt,
		String detail
) {
}
