package com.ace.common;

import java.time.LocalDateTime;

import com.ace.common.util.MaskingUtil;

import lombok.Builder;
import lombok.Getter;

// GlobalExceptionHandler 가 응답하는 예외 처리 응답 객체
@Getter
@Builder
public class ErrorResponse {

	private final LocalDateTime timestamp;
	private final int status;
	private final String code;
	private final String error;
	private final String message;
	private final String path;

	// message 가 null 이면 ErrorCode 의 기본 메시지를 사용
	// 예외 메시지에 개인정보가 섞여 들어올 수 있어 응답 직전에 마스킹
	public static ErrorResponse of(ErrorCode errorCode, String message, String path) {
		String resolved = (message != null && !message.isBlank())
				? message
				: errorCode.getDefaultMessage();

		return ErrorResponse.builder()
				.timestamp(LocalDateTime.now())
				.status(errorCode.getStatus().value())
				.code(errorCode.name())
				.error(errorCode.getStatus().getReasonPhrase())
				.message(MaskingUtil.mask(resolved))
				.path(path)
				.build();
	}

	@Override
	public String toString() {
		return "ErrorResponse [timestamp=" + timestamp + ", status=" + status + ", code=" + code
				+ ", error=" + error + ", message=" + message + ", path=" + path + "]";
	}
}
