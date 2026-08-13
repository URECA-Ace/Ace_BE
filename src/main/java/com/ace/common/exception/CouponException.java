package com.ace.common.exception;

import com.ace.common.ErrorCode;

import lombok.Getter;

@Getter
public class CouponException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final ErrorCode errorCode;

	public CouponException(ErrorCode errorCode) {
		super(errorCode.getDefaultMessage());
		this.errorCode = errorCode;
	}

	// 기본 메시지 대신 상세 사유를 담아야 할 때
	public CouponException(ErrorCode errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	public CouponException(ErrorCode errorCode, String message, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
	}
}
