package com.ace.common;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 에러 식별자 + HTTP 상태 + 기본 메시지를 한곳에서 관리
// 추가 필요한 에러는 이 곳에 추가
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

	// 발급 판정
	SOLD_OUT(HttpStatus.CONFLICT, "재고가 모두 소진되었습니다."),
	ALREADY_ISSUED(HttpStatus.CONFLICT, "이미 발급받은 쿠폰입니다."),
	DUPLICATE_REQUEST(HttpStatus.CONFLICT, "이미 처리된 요청입니다."),
	EVENT_NOT_OPEN(HttpStatus.BAD_REQUEST, "아직 발급 시작 전입니다."),
	EVENT_CLOSED(HttpStatus.BAD_REQUEST, "종료된 캠페인입니다."),

	// 조회
	EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "캠페인을 찾을 수 없습니다."),
	ISSUE_NOT_FOUND(HttpStatus.NOT_FOUND, "발급 내역을 찾을 수 없습니다."),
	RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),

	// 입력
	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
	INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "잘못된 요청 파라미터입니다."),
	MISSING_PARAMETER(HttpStatus.BAD_REQUEST, "필수 파라미터가 없습니다."),
	MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "요청 본문을 읽을 수 없습니다."),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다."),

	// 시스템
	ISSUE_PERSIST_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "발급 처리 중 오류가 발생했습니다."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

	private final HttpStatus status;
	private final String defaultMessage;
}
