package com.ace.consistency.rowlevel.dto;

import java.time.LocalDateTime;

public record RowValidationRequest<T>(
		LocalDateTime snapshotAt,
		T row
) {
}
