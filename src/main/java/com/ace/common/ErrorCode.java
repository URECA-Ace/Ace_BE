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
	IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "동일한 Idempotency-Key가 다른 요청에 사용되었습니다."),
	EVENT_NOT_OPEN(HttpStatus.BAD_REQUEST, "아직 발급 시작 전입니다."),
	EVENT_CLOSED(HttpStatus.BAD_REQUEST, "종료된 캠페인입니다."),
	EVENT_CONFIGURATION_CONFLICT(HttpStatus.CONFLICT, "동일 회차의 캠페인이 다른 설정으로 이미 존재합니다."),

	// 조회
	COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "쿠폰을 찾을 수 없습니다."),
	EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "캠페인을 찾을 수 없습니다."),
	ISSUE_NOT_FOUND(HttpStatus.NOT_FOUND, "발급 내역을 찾을 수 없습니다."),
	RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),

	// 입력
	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
	INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "잘못된 요청 파라미터입니다."),
	MISSING_PARAMETER(HttpStatus.BAD_REQUEST, "필수 파라미터가 없습니다."),
	MISSING_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "Idempotency-Key 헤더가 필요합니다."),
	INVALID_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "Idempotency-Key는 UUID 형식이어야 합니다."),
	MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "요청 본문을 읽을 수 없습니다."),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다."),

	// 캠페인 운영
	CAMPAIGN_CONFIG_CONFLICT(HttpStatus.CONFLICT,
			"이미 다른 설정으로 초기화된 캠페인입니다."),
	CAMPAIGN_NOT_INITIALIZABLE(HttpStatus.BAD_REQUEST,
			"초기화할 수 없는 회차입니다."),
	CAMPAIGN_INIT_FAILED(HttpStatus.INTERNAL_SERVER_ERROR,
			"캠페인 초기화에 실패했습니다."),
	
	// 상태 변경/ 관리
	INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "현재 상태에서 요청한 처리를 할 수 없습니다."),
	ALREADY_USED(HttpStatus.CONFLICT, "이미 사용된 쿠폰입니다."),
	NOT_YET_USED(HttpStatus.CONFLICT, "사용되지 않은 쿠폰은 취소할 수 없습니다."),
	ALREADY_EXPIRED(HttpStatus.CONFLICT, "만료된 쿠폰입니다."),

	// 시스템
	CAMPAIGN_INITIALIZATION_TEMPORARILY_UNAVAILABLE(
			HttpStatus.SERVICE_UNAVAILABLE,
			"쿠폰 캠페인 발급 상태를 초기화할 수 없습니다."),
	ISSUE_TEMPORARILY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "쿠폰 발급 요청을 일시적으로 처리할 수 없습니다."),
	EVENT_STATS_TEMPORARILY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "쿠폰 발급 현황을 일시적으로 조회할 수 없습니다."),
	ISSUE_PERSIST_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "발급 처리 중 오류가 발생했습니다."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

	// 정합성 검증
	UNSUPPORTED_SCOPE(HttpStatus.INTERNAL_SERVER_ERROR, "지원하지 않는 정합성 검증 스코프입니다."),
	CHECK_POSTPONED(HttpStatus.SERVICE_UNAVAILABLE, "처리 중인 데이터가 있어 정합성 검증을 보류합니다."),
	CHECK_IMPOSSIBLE(HttpStatus.GONE, "원본 데이터가 유실되어 정합성 검증을 수행할 수 없습니다.");

	private final HttpStatus status;
	private final String defaultMessage;
}
