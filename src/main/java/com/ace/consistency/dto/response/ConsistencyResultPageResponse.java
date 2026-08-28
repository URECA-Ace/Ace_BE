package com.ace.consistency.dto.response;

import java.util.List;

import org.springframework.data.domain.Page;

import com.ace.consistency.entity.VerificationResultEntity;

public record ConsistencyResultPageResponse(
		List<ConsistencyResultResponse> content,
		int page,
		int size,
		long totalElements,
		int totalPages,
		boolean hasNext) {

	public static ConsistencyResultPageResponse from(Page<VerificationResultEntity> result) {
		return new ConsistencyResultPageResponse(
				result.getContent().stream().map(ConsistencyResultResponse::from).toList(),
				result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(),
				result.hasNext());
	}
}
