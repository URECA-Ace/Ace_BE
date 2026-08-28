package com.ace.consistency.recovery.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import com.ace.consistency.recovery.RecoveryResult;

public record RecoveryHistoryPageResponse(
		List<RecoveryResultResponse> content,
		int page,
		int size,
		long totalElements,
		int totalPages,
		boolean hasNext) {

	public static RecoveryHistoryPageResponse from(Page<RecoveryResult> result) {
		return new RecoveryHistoryPageResponse(
				result.getContent().stream().map(RecoveryResultResponse::from).toList(),
				result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(),
				result.hasNext());
	}
}
