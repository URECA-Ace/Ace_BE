package com.ace.common;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
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
	public ResponseEntity<ApiResponse<Void>> handleCoupon(CouponException ex, HttpServletRequest request) {
		ErrorCode errorCode = ex.getErrorCode();

		// 5xx 는 서버 문제이므로 스택트레이스까지
		if (errorCode.getStatus().is5xxServerError()) {
			log.error("[{}] {} - {}", errorCode, request.getRequestURI(), MaskingUtil.mask(ex.getMessage()), ex);
		} else {
			log.warn("[{}] {} - {}", errorCode, request.getRequestURI(), MaskingUtil.mask(ex.getMessage()));
		}

		return build(errorCode, ex.getMessage());
	}

	// 400 : @Valid 입력 검증 실패
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
		String message = ex.getBindingResult().getFieldError() != null
				? ex.getBindingResult().getFieldError().getDefaultMessage()
				: null;

		return build(ErrorCode.INVALID_REQUEST, message);
	}

	// 400 : 경로변수/쿼리파라미터 타입 불일치
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
		return build(ErrorCode.INVALID_PARAMETER, "잘못된 요청 파라미터입니다: " + ex.getName());
	}

	// 400 : 필수 요청 파라미터 누락
	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException ex) {
		return build(ErrorCode.MISSING_PARAMETER, "필수 파라미터가 없습니다: " + ex.getParameterName());
	}

	// 400 : 요청 본문을 파싱할 수 없음 (깨진 JSON 등)
	// 본문에 개인정보가 섞여 있을 수 있어 예외 메시지를 응답에 X
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException ex) {
		return build(ErrorCode.MALFORMED_REQUEST, null);
	}

	// 405 / 415 : 클라이언트가 잘못된 메서드나 Content-Type 으로 호출
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
		return build(ErrorCode.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다: " + ex.getMethod());
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
		return build(ErrorCode.UNSUPPORTED_MEDIA_TYPE, null);
	}

	// 409 : UNIQUE 제약 위반
	@ExceptionHandler(DuplicateKeyException.class)
	public ResponseEntity<ApiResponse<Void>> handleDuplicateKey(
			DuplicateKeyException ex, HttpServletRequest request) {

		log.warn("중복 키: {}", request.getRequestURI());
		return build(ErrorCode.ALREADY_ISSUED, null);
	}

	// 500 : UNIQUE 외의 무결성 위반 (FK / NOT NULL / 길이 초과)
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(
			DataIntegrityViolationException ex, HttpServletRequest request) {

		log.error("무결성 제약 위반: {}", request.getRequestURI(), ex);
		return build(ErrorCode.INTERNAL_ERROR, null);
	}

	// 404 : 매칭되는 핸들러가 없는 URL (오타 경로 등)
	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
		return build(ErrorCode.RESOURCE_NOT_FOUND, null);
	}

	// ResponseStatusException 은 던진 쪽이 상태코드를 이미 정했으므로 그대로 통과
	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<ApiResponse<Void>> handleResponseStatus(ResponseStatusException ex) {
		HttpStatusCode statusCode = ex.getStatusCode();
		HttpStatus resolved = HttpStatus.resolve(statusCode.value());

		String code = resolved != null ? resolved.name() : "HTTP_" + statusCode.value();
		String message = ex.getReason() != null
				? ex.getReason()
				: (resolved != null ? resolved.getReasonPhrase() : "Error");

		return ResponseEntity.status(statusCode).body(ApiResponse.error(code, message));
	}

	// 500 : 위에서 매핑되지 않은 모든 예외
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleException(Exception ex, HttpServletRequest request) {
		log.error("처리되지 않은 예외: {}", request.getRequestURI(), ex);
		return build(ErrorCode.INTERNAL_ERROR, null);
	}

	private ResponseEntity<ApiResponse<Void>> build(ErrorCode errorCode, String message) {
		return ResponseEntity.status(errorCode.getStatus())
				.body(ApiResponse.error(errorCode, message));
	}
}
