package com.ace.consistency.dto.response;

import java.util.List;

import org.springframework.data.domain.Page;

import com.ace.consistency.entity.VerificationViolationEntity;

public record ConsistencyViolationPageResponse(
		List<ConsistencyViolationResponse> content,
		int page,
		int size,
		long totalElements,
		int totalPages,
		boolean hasNext) {

	public static ConsistencyViolationPageResponse from(Page<VerificationViolationEntity> result) {
		return new ConsistencyViolationPageResponse(
				result.getContent().stream().map(ConsistencyViolationResponse::from).toList(),
				result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(),
				result.hasNext());
	}
}
