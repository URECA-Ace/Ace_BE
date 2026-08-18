package com.ace.consistency.rowlevel.dto;

import com.ace.consistency.rowlevel.domain.ValidationStatus;

import java.time.LocalDateTime;
import java.util.List;

public record RowValidationResponse(
		String targetType,
		String targetId,
		LocalDateTime snapshotAt,
		ValidationStatus status,
		long passCount,
		long failCount,
		long warningCount,
		List<ValidationResult> results
) {
}
