package com.ace.coupon.dto.response;

import com.ace.coupon.enums.IssueFailureAction;

public record IssueFailureActionResultResponse(
		IssueFailureAction action,
		String outcome,
		IssueFailureItemResponse failure) {
}
