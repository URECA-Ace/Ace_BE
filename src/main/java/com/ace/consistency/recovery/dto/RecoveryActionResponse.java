package com.ace.consistency.recovery.dto;

import com.ace.consistency.recovery.enums.RecoveryAction;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 관리자 화면에 노출할 복구 액션. action은 recover 요청 시 그대로 보낼 값, label은 표시 문구다. */
@Getter
@AllArgsConstructor
public class RecoveryActionResponse {

	private final RecoveryAction action;
	private final String label;

	public static RecoveryActionResponse from(RecoveryAction action) {
		return new RecoveryActionResponse(action, action.getLabel());
	}
}
