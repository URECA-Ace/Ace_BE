package com.ace.common;

import java.time.LocalDateTime;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.ace.common.util.MaskingUtil;
import com.fasterxml.jackson.annotation.JsonInclude;

// 공통 응답(성공과 실패를 같은 형태로)
public record ApiResponse<T>(
		String result,
		T data,
		ErrorBody error,
		LocalDateTime timestamp,
		String path) {

	private static final String SUCCESS = "success";
	private static final String ERROR = "error";

	public record ErrorBody(
			String code,
			String message,
			@JsonInclude(JsonInclude.Include.NON_NULL) String incidentId) {
	}

	public static <T> ApiResponse<T> success(T data) {
		return new ApiResponse<>(SUCCESS, data, null, LocalDateTime.now(), currentPath());
	}

	// 반환할 데이터가 없는 성공 응답 (예: 삭제)
	public static ApiResponse<Void> success() {
		return new ApiResponse<>(SUCCESS, null, null, LocalDateTime.now(), currentPath());
	}

	// message 가 비어 있으면 ErrorCode 의 기본 메시지를 사용
	public static ApiResponse<Void> error(ErrorCode errorCode, String message) {
		return error(errorCode, message, null);
	}

	public static ApiResponse<Void> error(ErrorCode errorCode, String message, String incidentId) {
		String resolved = errorCode.getStatus().is5xxServerError()
				? errorCode.getDefaultMessage()
				: (message != null && !message.isBlank())
				? message
				: errorCode.getDefaultMessage();

		return error(errorCode.name(), resolved, incidentId);
	}

	// ErrorCode 로 표현할 수 없는 경우
	public static ApiResponse<Void> error(String code, String message) {
		return error(code, message, null);
	}

	public static ApiResponse<Void> error(String code, String message, String incidentId) {
		return new ApiResponse<>(ERROR, null,
				new ErrorBody(code, MaskingUtil.mask(message), incidentId),
				LocalDateTime.now(), currentPath());
	}

	private static String currentPath() {
		RequestAttributes attributes = RequestContextHolder.getRequestAttributes();

		if (attributes instanceof ServletRequestAttributes servletAttributes) {
			return servletAttributes.getRequest().getRequestURI();
		}
		return null;
	}
}
