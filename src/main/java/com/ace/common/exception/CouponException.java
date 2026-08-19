package com.ace.common.exception;

import com.ace.common.ErrorCode;

import lombok.Getter;

@Getter
public class CouponException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final ErrorCode errorCode;

	/**
	 * 응답에 노출할 사고 식별자. 예외를 던지는 쪽이 이미 로그나 실패 기록에 같은 값을 남긴 경우에만 채운다.
	 * 비어 있으면 예외 핸들러가 새로 발급한다.
	 */
	private final String incidentId;

	public CouponException(ErrorCode errorCode) {
		this(errorCode, errorCode.getDefaultMessage(), null, null);
	}

	// 기본 메시지 대신 상세 사유를 담아야 할 때
	public CouponException(ErrorCode errorCode, String message) {
		this(errorCode, message, null, null);
	}

	public CouponException(ErrorCode errorCode, String message, Throwable cause) {
		this(errorCode, message, cause, null);
	}

	public CouponException(ErrorCode errorCode, String message, Throwable cause, String incidentId) {
		super(message, cause);
		this.errorCode = errorCode;
		this.incidentId = incidentId;
	}
}
