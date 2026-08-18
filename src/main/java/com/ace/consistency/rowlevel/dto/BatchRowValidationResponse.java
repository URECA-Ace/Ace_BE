package com.ace.consistency.rowlevel.dto;

import java.time.LocalDateTime;
import java.util.List;

public record BatchRowValidationResponse(
		String targetType,
		LocalDateTime snapshotAt,
		long maxIssueId,
		int pageSize,
		long processedRows,
		long passRows,
		long failRows,
		long warningRows,
		List<RowValidationResponse> failureSamples
) {
}
