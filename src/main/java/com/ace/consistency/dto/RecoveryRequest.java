package com.ace.consistency.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 관리자가 위반 결과 화면에서 선택한 복구 액션. 아직 정의되지 않은 값이면 400으로 응답한다. */
@Getter
@AllArgsConstructor
public class RecoveryRequest {

	private final String action;
}
