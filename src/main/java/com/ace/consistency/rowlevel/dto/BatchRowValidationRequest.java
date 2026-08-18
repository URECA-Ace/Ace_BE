package com.ace.consistency.rowlevel.dto;

import java.time.LocalDateTime;

public record BatchRowValidationRequest(
		LocalDateTime snapshotAt,
		Long maxIssueId,
		Integer pageSize,
		Integer failureSampleLimit
) {
}
