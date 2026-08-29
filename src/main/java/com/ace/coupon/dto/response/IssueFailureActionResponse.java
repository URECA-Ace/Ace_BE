package com.ace.coupon.dto.response;

import com.ace.coupon.enums.IssueFailureAction;

// action 은 실행 요청 시 그대로 보낼 값, label 은 표시 문구
public record IssueFailureActionResponse(
		IssueFailureAction action,
		String label,
		boolean reasonRequired) {

	public static IssueFailureActionResponse from(IssueFailureAction action) {
		return new IssueFailureActionResponse(
				action, action.getLabel(), action == IssueFailureAction.RESOLVE);
	}
}
