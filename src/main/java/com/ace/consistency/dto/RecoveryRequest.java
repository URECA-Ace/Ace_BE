package com.ace.consistency.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 관리자가 위반 결과 화면에서 선택한 복구 액션. action이 아직 정의되지 않은 값이면 400으로 응답한다.
 * eventId는 ALL 스코프 검증 결과를 복구할 때 어느 위반 이벤트를 복구할지 지정하는 값으로,
 * EVENT 스코프 결과에는 필요 없다(null 허용).
 */
@Getter
@AllArgsConstructor
public class RecoveryRequest {

	private final String action;
	private final Long eventId;
}
