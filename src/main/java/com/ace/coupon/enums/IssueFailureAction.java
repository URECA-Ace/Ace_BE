package com.ace.coupon.enums;

import lombok.Getter;

// 운영자가 실패 한 건에 취할 수 있는 조치
@Getter
public enum IssueFailureAction {

	// 자동 재처리기와 같은 절차를 지금 한 번 실행
	RETRY("재시도"),

	// 자동으로 회수할 수 없는 건을 사람이 확인하고 닫는다
	RESOLVE("확인 후 종결");

	private final String label;

	IssueFailureAction(String label) {
		this.label = label;
	}
}
