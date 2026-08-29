package com.ace.consistency.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ConsistencyScheduleUpdateRequest(@NotNull @Min(1000) Long intervalMs) {
}
