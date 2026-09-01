package com.ace.consistency.recovery.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import jakarta.validation.constraints.NotBlank;

/**
 * 관리자가 위반 결과 화면에서 선택한 복구 액션. action이 아직 정의되지 않은 값이면 400으로 응답한다.
 * ALL 스코프 검증 결과의 복구 대상 이벤트는 서버가 검증 결과에 저장된 위반 목록에서 직접
 * 판단하므로 호출부가 eventId를 지정할 필요가 없다.
 */
@Getter
@AllArgsConstructor
public class RecoveryRequest {

	@NotBlank(message = "복구 액션은 필수입니다.")
	private final String action;
}
