package com.ace.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    INVALID_REQUEST(
            HttpStatus.BAD_REQUEST,
            "INVALID_REQUEST",
            "요청값이 올바르지 않습니다."
    ),

    MISSING_IDEMPOTENCY_KEY(
            HttpStatus.BAD_REQUEST,
            "MISSING_IDEMPOTENCY_KEY",
            "Idempotency-Key 헤더가 필요합니다."
    ),

    INVALID_IDEMPOTENCY_KEY(
            HttpStatus.BAD_REQUEST,
            "INVALID_IDEMPOTENCY_KEY",
            "Idempotency-Key는 UUID 형식이어야 합니다."
    ),

    EVENT_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "EVENT_NOT_FOUND",
            "쿠폰 이벤트를 찾을 수 없습니다."
    ),

    EVENT_NOT_OPEN(
            HttpStatus.CONFLICT,
            "EVENT_NOT_OPEN",
            "현재 발급 가능한 쿠폰 이벤트가 아닙니다."
    ),

    ISSUE_DUPLICATED(
            HttpStatus.CONFLICT,
            "ISSUE_DUPLICATED",
            "이미 쿠폰을 발급받은 사용자입니다."
    ),

    ISSUE_SOLD_OUT(
            HttpStatus.CONFLICT,
            "ISSUE_SOLD_OUT",
            "쿠폰 재고가 소진되었습니다."
    ),

    IDEMPOTENCY_CONFLICT(
            HttpStatus.CONFLICT,
            "IDEMPOTENCY_CONFLICT",
            "동일한 Idempotency-Key가 다른 요청에 사용되었습니다."
    ),

    ISSUE_TEMPORARILY_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "ISSUE_TEMPORARILY_UNAVAILABLE",
            "쿠폰 발급 요청을 일시적으로 처리할 수 없습니다."
    ),

    INTERNAL_SERVER_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_SERVER_ERROR",
            "서버 내부 오류가 발생했습니다."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    ErrorCode(
            HttpStatus httpStatus,
            String code,
            String message
    ) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}