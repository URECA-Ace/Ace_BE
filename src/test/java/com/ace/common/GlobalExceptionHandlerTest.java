package com.ace.common;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.ace.common.exception.CouponException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@DisplayName("공통 응답 및 전역 예외 처리")
class GlobalExceptionHandlerTest {

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	@DisplayName("성공 응답은 result=success 이고 error 가 null 이다")
	void successEnvelope() throws Exception {
		mockMvc.perform(get("/test/success"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result").value("success"))
				.andExpect(jsonPath("$.data.name").value("데이터 무제한 제공"))
				.andExpect(jsonPath("$.data.sequence").value(4821))
				.andExpect(jsonPath("$.error").value(nullValue()))
				.andExpect(jsonPath("$.timestamp").exists())
				.andExpect(jsonPath("$.path").value("/test/success"));
	}

	@Test
	@DisplayName("데이터 없는 성공 응답도 같은 envelope 를 유지한다")
	void successEnvelopeWithoutData() throws Exception {
		mockMvc.perform(get("/test/success-empty"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result").value("success"))
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.error").value(nullValue()));
	}

	@Test
	@DisplayName("재고 소진은 409 SOLD_OUT 으로 응답한다")
	void soldOut() throws Exception {
		mockMvc.perform(get("/test/sold-out"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.result").value("error"))
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.error.code").value("SOLD_OUT"))
				.andExpect(jsonPath("$.error.message").value("재고가 모두 소진되었습니다."))
				.andExpect(jsonPath("$.timestamp").exists())
				.andExpect(jsonPath("$.path").value("/test/sold-out"));
	}

	@Test
	@DisplayName("같은 409 라도 code 로 중복 발급과 재고 소진을 구분한다")
	void alreadyIssued() throws Exception {
		mockMvc.perform(get("/test/already-issued"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("ALREADY_ISSUED"));
	}

	@Test
	@DisplayName("쿠폰 발급 UNIQUE 제약 위반은 409 ALREADY_ISSUED 로 변환한다")
	void duplicateKey() throws Exception {
		mockMvc.perform(get("/test/duplicate-key"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("ALREADY_ISSUED"));
	}

	@Test
	@DisplayName("UNIQUE 외의 무결성 위반(FK 등)은 409 가 아니라 500 으로 응답한다")
	void otherIntegrityViolation() throws Exception {
		// 409 로 응답하면 부하테스트에서 정상 흐름(재고 소진)에 묻혀 버그가 발견되지 않는다
		mockMvc.perform(get("/test/fk-violation"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.error.message").value("서버 내부 오류가 발생했습니다."));
	}

	@Test
	@DisplayName("request_id UNIQUE 제약 위반은 중복 요청으로 구분한다")
	void duplicateRequestId() throws Exception {
		mockMvc.perform(get("/test/duplicate-request-id"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("DUPLICATE_REQUEST"));
	}

	@Test
	@DisplayName("발급 순번 UNIQUE 충돌은 저장 실패로 구분한다")
	void duplicateIssueSequence() throws Exception {
		mockMvc.perform(get("/test/duplicate-issue-sequence"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error.code").value("ISSUE_PERSIST_FAILED"));
	}

	@Test
	@DisplayName("@Valid 검증 실패는 400 INVALID_REQUEST 로 응답한다")
	void validationFailure() throws Exception {
		mockMvc.perform(post("/test/validate")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"requestId\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.error.message").value("requestId 는 필수입니다."));
	}

	@Test
	@DisplayName("경로변수 타입 불일치는 400 INVALID_PARAMETER 로 응답한다")
	void typeMismatch() throws Exception {
		mockMvc.perform(get("/test/events/abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
	}

	@Test
	@DisplayName("지원하지 않는 HTTP 메서드는 500 이 아니라 405 로 응답한다")
	void methodNotAllowed() throws Exception {
		mockMvc.perform(delete("/test/success"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
	}

	@Test
	@DisplayName("깨진 JSON 본문은 500 이 아니라 400 MALFORMED_REQUEST 로 응답한다")
	void malformedJson() throws Exception {
		mockMvc.perform(post("/test/validate")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"requestId\":"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"))
				// 본문에 개인정보가 섞여 있을 수 있으므로 파싱 오류 상세를 노출하지 않는다
				.andExpect(jsonPath("$.error.message").value("요청 본문을 읽을 수 없습니다."));
	}

	@Test
	@DisplayName("지원하지 않는 Content-Type 은 500 이 아니라 415 로 응답한다")
	void unsupportedMediaType() throws Exception {
		mockMvc.perform(post("/test/validate")
						.contentType(MediaType.TEXT_PLAIN)
						.content("requestId=abc"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
	}

	@Test
	@DisplayName("필수 파라미터 누락은 500 이 아니라 400 MISSING_PARAMETER 로 응답한다")
	void missingParameter() throws Exception {
		mockMvc.perform(get("/test/search"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MISSING_PARAMETER"))
				.andExpect(jsonPath("$.error.message").value("필수 파라미터가 없습니다: eventId"));
	}

	@Test
	@DisplayName("매핑되지 않은 예외는 500 INTERNAL_ERROR 로 응답하고 내부 메시지를 노출하지 않는다")
	void unhandledException() throws Exception {
		mockMvc.perform(get("/test/boom"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.error.message").value("서버 내부 오류가 발생했습니다."));
	}

	@Test
	@DisplayName("5xx 비즈니스 예외도 상세 메시지를 응답에 노출하지 않는다")
	void serverCouponExceptionDoesNotLeakMessage() throws Exception {
		mockMvc.perform(get("/test/internal-coupon-error"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error.code").value("ISSUE_PERSIST_FAILED"))
				.andExpect(jsonPath("$.error.message").value("발급 처리 중 오류가 발생했습니다."));
	}

	@Test
	@DisplayName("예외 메시지에 섞인 개인정보는 응답에서 마스킹한다")
	void masksPersonalInfoInMessage() throws Exception {
		mockMvc.perform(get("/test/leaky"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("ISSUE_NOT_FOUND"))
				.andExpect(jsonPath("$.error.message")
						.value("발급 내역 없음: use****@test.com / 010-****-5678"));
	}

	@Test
	@DisplayName("ResponseStatusException 은 던진 쪽의 상태코드를 유지한다")
	void responseStatusException() throws Exception {
		mockMvc.perform(get("/test/response-status"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.result").value("error"))
				.andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
				.andExpect(jsonPath("$.error.message").value("서버 내부 오류가 발생했습니다."));
	}

	@Test
	@DisplayName("비표준 상태코드에서도 응답 포맷을 유지한다")
	void nonStandardStatusCode() throws Exception {
		// HttpStatus.valueOf(499) 는 IllegalArgumentException 을 던진다.
		// 핸들러 안에서 터지면 응답 포맷을 우회하므로 resolve() 로 방어한다.
		mockMvc.perform(get("/test/non-standard-status"))
				.andExpect(status().is(499))
				.andExpect(jsonPath("$.result").value("error"))
				.andExpect(jsonPath("$.error.code").value("HTTP_499"))
				.andExpect(jsonPath("$.error.message").value("요청을 처리할 수 없습니다."))
				.andExpect(jsonPath("$.path").value("/test/non-standard-status"));
	}

	@RestController
	static class TestController {

		@GetMapping("/test/success")
		ApiResponse<TestData> success() {
			return ApiResponse.success(new TestData("데이터 무제한 제공", 4821));
		}

		@GetMapping("/test/success-empty")
		ApiResponse<Void> successEmpty() {
			return ApiResponse.success();
		}

		@GetMapping("/test/sold-out")
		void soldOut() {
			throw new CouponException(ErrorCode.SOLD_OUT);
		}

		@GetMapping("/test/already-issued")
		void alreadyIssued() {
			throw new CouponException(ErrorCode.ALREADY_ISSUED);
		}

		@GetMapping("/test/duplicate-key")
		void duplicateKey() {
			throw new DataIntegrityViolationException(
					"Duplicate entry for key 'uk_coupon_issue_event_user'");
		}

		@GetMapping("/test/fk-violation")
		void fkViolation() {
			throw new DataIntegrityViolationException(
					"Cannot add or update a child row: a foreign key constraint fails");
		}

		@GetMapping("/test/duplicate-request-id")
		void duplicateRequestId() {
			throw new DataIntegrityViolationException(
					"Duplicate entry for key 'uk_coupon_issue_request_id'");
		}

		@GetMapping("/test/duplicate-issue-sequence")
		void duplicateIssueSequence() {
			throw new DataIntegrityViolationException(
					"Duplicate entry for key 'uk_coupon_issue_event_sequence'");
		}

		@GetMapping("/test/response-status")
		void responseStatus() {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "일시적으로 이용할 수 없습니다.");
		}

		@GetMapping("/test/non-standard-status")
		void nonStandardStatus() {
			throw new ResponseStatusException(HttpStatusCode.valueOf(499), "클라이언트가 요청을 취소했습니다.");
		}

		@GetMapping("/test/events/{eventId}")
		void typeMismatch(@PathVariable Long eventId) {
			// 진입 전 MethodArgumentTypeMismatchException 발생
		}

		@GetMapping("/test/boom")
		void boom() {
			throw new IllegalStateException("내부 상태 오류 - 응답에 노출되면 안 됨");
		}

		@GetMapping("/test/internal-coupon-error")
		void internalCouponError() {
			throw new CouponException(ErrorCode.ISSUE_PERSIST_FAILED,
					"SQL failed for user1@test.com / 010-1234-5678");
		}

		@GetMapping("/test/leaky")
		void leaky() {
			throw new CouponException(ErrorCode.ISSUE_NOT_FOUND,
					"발급 내역 없음: user1@test.com / 010-1234-5678");
		}

		@PostMapping("/test/validate")
		void validate(@Valid @RequestBody TestRequest request) {
		}

		@GetMapping("/test/search")
		void search(@RequestParam Long eventId) {
			// eventId 누락 시 MissingServletRequestParameterException 발생
		}
	}

	record TestData(String name, int sequence) {
	}

	record TestRequest(@NotBlank(message = "requestId 는 필수입니다.") String requestId) {
	}
}
