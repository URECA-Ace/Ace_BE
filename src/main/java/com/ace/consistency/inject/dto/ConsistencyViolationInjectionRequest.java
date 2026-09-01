package com.ace.consistency.inject.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ConsistencyViolationInjectionRequest(@NotBlank String checkName, @NotNull Long eventId) {
}
