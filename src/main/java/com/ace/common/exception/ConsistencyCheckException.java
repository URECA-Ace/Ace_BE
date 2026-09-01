package com.ace.common.exception;

import com.ace.common.ErrorCode;

import lombok.Getter;

@Getter
public class ConsistencyCheckException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final ErrorCode errorCode;

	public ConsistencyCheckException(ErrorCode errorCode) {
		this(errorCode, errorCode.getDefaultMessage(), null);
	}

	// 기본 메시지 대신 상세 사유를 담아야 할 때
	public ConsistencyCheckException(ErrorCode errorCode, String message) {
		this(errorCode, message, null);
	}

	public ConsistencyCheckException(ErrorCode errorCode, String message, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
	}
}
