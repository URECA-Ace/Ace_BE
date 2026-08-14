package com.ace.common;

import java.util.Locale;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.ace.common.exception.CouponException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
	private static final String ISSUE_USER_CONSTRAINT = "uk_coupon_issue_event_user";
	private static final String REQUEST_ID_CONSTRAINT = "uk_coupon_issue_request_id";
	private static final String MESSAGE_ID_CONSTRAINT = "uk_coupon_issue_message_id";

	@ExceptionHandler(CouponException.class)
	public ResponseEntity<ApiResponse<Void>> handleCoupon(
			CouponException ex,
			HttpServletRequest request) {
		ErrorCode errorCode = ex.getErrorCode();

		if (errorCode.getStatus().is5xxServerError()) {
			logServerError(errorCode.name(), ex, request);
		} else {
			log.warn("[{}] request rejected: method={}, path={}",
					errorCode, request.getMethod(), request.getRequestURI());
		}

		return build(errorCode, ex.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
		String message = ex.getBindingResult().getFieldError() != null
				? ex.getBindingResult().getFieldError().getDefaultMessage()
				: null;
		return build(ErrorCode.INVALID_REQUEST, message);
	}

	@ExceptionHandler(BindException.class)
	public ResponseEntity<ApiResponse<Void>> handleBinding(BindException ex) {
		return build(ErrorCode.INVALID_REQUEST, null);
	}

	@ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
	public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
			jakarta.validation.ConstraintViolationException ex) {
		String message = ex.getConstraintViolations().stream()
				.findFirst()
				.map(ConstraintViolation::getMessage)
				.orElse(null);
		return build(ErrorCode.INVALID_REQUEST, message);
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
		return build(ErrorCode.INVALID_PARAMETER, "잘못된 요청 파라미터입니다: " + ex.getName());
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException ex) {
		return build(ErrorCode.MISSING_PARAMETER, "필수 파라미터가 없습니다: " + ex.getParameterName());
	}

	@ExceptionHandler(MissingRequestHeaderException.class)
	public ResponseEntity<ApiResponse<Void>> handleMissingHeader(MissingRequestHeaderException ex) {
		ErrorCode errorCode = IDEMPOTENCY_KEY.equalsIgnoreCase(ex.getHeaderName())
				? ErrorCode.MISSING_IDEMPOTENCY_KEY
				: ErrorCode.INVALID_REQUEST;
		return build(errorCode, null);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException ex) {
		return build(ErrorCode.MALFORMED_REQUEST, null);
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
		return build(ErrorCode.METHOD_NOT_ALLOWED, null);
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
		return build(ErrorCode.UNSUPPORTED_MEDIA_TYPE, null);
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(
			DataIntegrityViolationException ex,
			HttpServletRequest request) {
		String constraintName = findConstraintName(ex);

		if (ISSUE_USER_CONSTRAINT.equals(constraintName)) {
			log.warn("[{}] duplicate coupon issue blocked: method={}, path={}",
					ErrorCode.ALREADY_ISSUED, request.getMethod(), request.getRequestURI());
			return build(ErrorCode.ALREADY_ISSUED, null);
		}
		if (REQUEST_ID_CONSTRAINT.equals(constraintName)
				|| MESSAGE_ID_CONSTRAINT.equals(constraintName)) {
			log.warn("[{}] duplicate request blocked: method={}, path={}",
					ErrorCode.DUPLICATE_REQUEST, request.getMethod(), request.getRequestURI());
			return build(ErrorCode.DUPLICATE_REQUEST, null);
		}

		logServerError(ErrorCode.INTERNAL_ERROR.name(), ex, request);
		return build(ErrorCode.INTERNAL_ERROR, null);
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
		return build(ErrorCode.RESOURCE_NOT_FOUND, null);
	}

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<ApiResponse<Void>> handleResponseStatus(
			ResponseStatusException ex,
			HttpServletRequest request) {
		return handleSpringError(ex, request);
	}

	@ExceptionHandler(ErrorResponseException.class)
	public ResponseEntity<ApiResponse<Void>> handleSpringError(
			ErrorResponseException ex,
			HttpServletRequest request) {
		HttpStatusCode statusCode = ex.getStatusCode();
		if (statusCode.is5xxServerError()) {
			logServerError("HTTP_" + statusCode.value(), ex, request);
			HttpStatus status = HttpStatus.resolve(statusCode.value());
			String code = status != null ? status.name() : "HTTP_" + statusCode.value();
			return ResponseEntity.status(statusCode)
					.body(ApiResponse.error(code, ErrorCode.INTERNAL_ERROR.getDefaultMessage()));
		}

		HttpStatus status = HttpStatus.resolve(statusCode.value());
		String code = status != null ? status.name() : "HTTP_" + statusCode.value();
		String message = status != null ? status.getReasonPhrase() : "요청을 처리할 수 없습니다.";
		return ResponseEntity.status(statusCode).body(ApiResponse.error(code, message));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleException(
			Exception ex,
			HttpServletRequest request) {
		logServerError(ErrorCode.INTERNAL_ERROR.name(), ex, request);
		return build(ErrorCode.INTERNAL_ERROR, null);
	}

	private String findConstraintName(Throwable throwable) {
		Throwable current = throwable;
		while (current != null) {
			if (current instanceof ConstraintViolationException constraintViolation
					&& constraintViolation.getConstraintName() != null) {
				return constraintViolation.getConstraintName().toLowerCase(Locale.ROOT);
			}

			String message = current.getMessage();
			if (message != null) {
				String normalized = message.toLowerCase(Locale.ROOT);
				for (String knownConstraint : new String[]{
						ISSUE_USER_CONSTRAINT, REQUEST_ID_CONSTRAINT, MESSAGE_ID_CONSTRAINT}) {
					if (normalized.contains(knownConstraint)) {
						return knownConstraint;
					}
				}
			}
			current = current.getCause();
		}
		return null;
	}

	private void logServerError(String code, Throwable ex, HttpServletRequest request) {
		String incidentId = UUID.randomUUID().toString();
		log.error("[{}] server error: incidentId={}, method={}, path={}, exceptionType={}",
				code, incidentId, request.getMethod(), request.getRequestURI(), ex.getClass().getName());
	}

	private ResponseEntity<ApiResponse<Void>> build(ErrorCode errorCode, String message) {
		return ResponseEntity.status(errorCode.getStatus())
				.body(ApiResponse.error(errorCode, message));
	}
}
