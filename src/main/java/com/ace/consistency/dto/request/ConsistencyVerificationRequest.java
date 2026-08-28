package com.ace.consistency.dto.request;

import java.time.LocalDateTime;
import java.util.List;

import com.ace.consistency.common.Scope;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record ConsistencyVerificationRequest(
		@NotNull @Valid ScopeRequest scope,
		@NotEmpty List<@NotBlank String> checkNames) {

	public record ScopeRequest(
			@NotNull Scope.ScopeType type,
			Long eventId,
			LocalDateTime from,
			LocalDateTime to) {
	}
}
