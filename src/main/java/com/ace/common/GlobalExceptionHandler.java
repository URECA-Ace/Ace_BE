package com.ace.common;

import java.time.LocalDateTime;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.ace.common.exception.CouponException;
import com.ace.common.util.MaskingUtil;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

// 프로젝트 전체 예외 처리자
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(CouponException.class)
	public ResponseEntity<ErrorResponse> handleCoupon(CouponException ex, HttpServletRequest request) {
		ErrorCode errorCode = ex.getErrorCode();

		// 5xx 는 서버 문제이므로 스택트레이스까지
		if (errorCode.getStatus().is5xxServerError()) {
			log.error("[{}] {} - {}", errorCode, request.getRequestURI(), MaskingUtil.mask(ex.getMessage()), ex);
		} else {
			log.warn("[{}] {} - {}", errorCode, request.getRequestURI(), MaskingUtil.mask(ex.getMessage()));
		}

		return build(errorCode, ex.getMessage(), request);
	}

	// 400 : @Valid 입력 검증 실패
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(
			MethodArgumentNotValidException ex, HttpServletRequest request) {

		String message = ex.getBindingResult().getFieldError() != null
				? ex.getBindingResult().getFieldError().getDefaultMessage()
				: null;

		return build(ErrorCode.INVALID_REQUEST, message, request);
	}

	// 400 : 경로변수/쿼리파라미터 타입 불일치
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleTypeMismatch(
			MethodArgumentTypeMismatchException ex, HttpServletRequest request) {

		return build(ErrorCode.INVALID_PARAMETER, "잘못된 요청 파라미터입니다: " + ex.getName(), request);
	}

	// 409 : UNIQUE 제약 위반
	@ExceptionHandler(DuplicateKeyException.class)
	public ResponseEntity<ErrorResponse> handleDuplicateKey(
			DuplicateKeyException ex, HttpServletRequest request) {

		log.warn("중복 키: {}", request.getRequestURI());
		return build(ErrorCode.ALREADY_ISSUED, null, request);
	}

	// 500 : UNIQUE 외의 무결성 위반 (FK / NOT NULL / 길이 초과)
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ErrorResponse> handleDataIntegrity(
			DataIntegrityViolationException ex, HttpServletRequest request) {

		log.error("무결성 제약 위반: {}", request.getRequestURI(), ex);
		return build(ErrorCode.INTERNAL_ERROR, null, request);
	}

	// 404 : 매칭되는 핸들러가 없는 URL (오타 경로 등)
	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ErrorResponse> handleNoResource(
			NoResourceFoundException ex, HttpServletRequest request) {

		return build(ErrorCode.RESOURCE_NOT_FOUND, null, request);
	}

	// ResponseStatusException 은 던진 쪽이 상태코드를 이미 정했으므로 그대로 통과
	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<ErrorResponse> handleResponseStatus(
			ResponseStatusException ex, HttpServletRequest request) {

		HttpStatusCode statusCode = ex.getStatusCode();
		HttpStatus resolved = HttpStatus.resolve(statusCode.value());

		String codeName = resolved != null ? resolved.name() : "HTTP_" + statusCode.value();
		String reason = resolved != null ? resolved.getReasonPhrase() : "Error";
		String message = ex.getReason() != null ? ex.getReason() : reason;

		ErrorResponse body = ErrorResponse.builder()
				.timestamp(LocalDateTime.now())
				.status(statusCode.value())
				.code(codeName)
				.error(reason)
				.message(MaskingUtil.mask(message))
				.path(request.getRequestURI())
				.build();

		return ResponseEntity.status(statusCode).body(body);
	}

	// 500 : 위에서 매핑되지 않은 모든 예외
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleException(Exception ex, HttpServletRequest request) {
		log.error("처리되지 않은 예외: {}", request.getRequestURI(), ex);
		return build(ErrorCode.INTERNAL_ERROR, null, request);
	}

	private ResponseEntity<ErrorResponse> build(ErrorCode errorCode, String message, HttpServletRequest request) {
		ErrorResponse body = ErrorResponse.of(errorCode, message, request.getRequestURI());
		return ResponseEntity.status(errorCode.getStatus()).body(body);
	}
}
