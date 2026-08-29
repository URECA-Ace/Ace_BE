package com.ace.coupon.dto.response;

import java.util.List;

import org.springframework.data.domain.Page;

import com.ace.coupon.entity.IssueFailureLog;

public record IssueFailurePageResponse(
		List<IssueFailureItemResponse> content,
		int page,
		int size,
		long totalElements,
		int totalPages,
		boolean hasNext) {

	public static IssueFailurePageResponse from(Page<IssueFailureLog> page) {
		return new IssueFailurePageResponse(
				page.getContent().stream().map(IssueFailureItemResponse::from).toList(),
				page.getNumber(),
				page.getSize(),
				page.getTotalElements(),
				page.getTotalPages(),
				page.hasNext());
	}
}
