package com.ace.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.ace.common.exception.CouponException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@DisplayName("전역 예외 처리")
class GlobalExceptionHandlerTest {

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	@DisplayName("재고 소진은 409 SOLD_OUT 으로 응답한다")
	void soldOut() throws Exception {
		mockMvc.perform(get("/test/sold-out"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.code").value("SOLD_OUT"))
				.andExpect(jsonPath("$.error").value("Conflict"))
				.andExpect(jsonPath("$.message").value("재고가 모두 소진되었습니다."))
				.andExpect(jsonPath("$.path").value("/test/sold-out"))
				.andExpect(jsonPath("$.timestamp").exists());
	}

	@Test
	@DisplayName("같은 409 라도 code 로 중복 발급과 재고 소진을 구분한다")
	void alreadyIssued() throws Exception {
		mockMvc.perform(get("/test/already-issued"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ALREADY_ISSUED"));
	}

	@Test
	@DisplayName("UNIQUE 제약 위반은 409 ALREADY_ISSUED 로 변환한다")
	void duplicateKey() throws Exception {
		mockMvc.perform(get("/test/duplicate-key"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ALREADY_ISSUED"));
	}

	@Test
	@DisplayName("UNIQUE 외의 무결성 위반(FK 등)은 409 가 아니라 500 으로 응답한다")
	void otherIntegrityViolation() throws Exception {
		// 409 로 응답하면 부하테스트에서 정상 흐름(재고 소진)에 묻혀 버그가 발견되지 않는다
		mockMvc.perform(get("/test/fk-violation"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."));
	}

	@Test
	@DisplayName("@Valid 검증 실패는 400 INVALID_REQUEST 로 응답한다")
	void validationFailure() throws Exception {
		mockMvc.perform(post("/test/validate")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"requestId\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.message").value("requestId 는 필수입니다."));
	}

	@Test
	@DisplayName("경로변수 타입 불일치는 400 INVALID_PARAMETER 로 응답한다")
	void typeMismatch() throws Exception {
		mockMvc.perform(get("/test/events/abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
	}

	@Test
	@DisplayName("매핑되지 않은 예외는 500 INTERNAL_ERROR 로 응답하고 내부 메시지를 노출하지 않는다")
	void unhandledException() throws Exception {
		mockMvc.perform(get("/test/boom"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."));
	}

	@Test
	@DisplayName("예외 메시지에 섞인 개인정보는 응답에서 마스킹한다")
	void masksPersonalInfoInMessage() throws Exception {
		mockMvc.perform(get("/test/leaky"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ISSUE_NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("발급 내역 없음: use****@test.com / 010-****-5678"));
	}

	@Test
	@DisplayName("ResponseStatusException 은 던진 쪽의 상태코드를 유지한다")
	void responseStatusException() throws Exception {
		mockMvc.perform(get("/test/response-status"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.status").value(503))
				.andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
				.andExpect(jsonPath("$.message").value("일시적으로 이용할 수 없습니다."));
	}

	@Test
	@DisplayName("비표준 상태코드에서도 응답 포맷을 유지한다")
	void nonStandardStatusCode() throws Exception {
		mockMvc.perform(get("/test/non-standard-status"))
				.andExpect(status().is(499))
				.andExpect(jsonPath("$.status").value(499))
				.andExpect(jsonPath("$.code").value("HTTP_499"))
				.andExpect(jsonPath("$.error").value("Error"))
				.andExpect(jsonPath("$.message").value("클라이언트가 요청을 취소했습니다."))
				.andExpect(jsonPath("$.path").value("/test/non-standard-status"));
	}

	@RestController
	static class TestController {

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
			throw new DuplicateKeyException("Duplicate entry for key 'uk_issue_user'");
		}

		@GetMapping("/test/fk-violation")
		void fkViolation() {
			throw new DataIntegrityViolationException(
					"Cannot add or update a child row: a foreign key constraint fails");
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

		@GetMapping("/test/leaky")
		void leaky() {
			throw new CouponException(ErrorCode.ISSUE_NOT_FOUND,
					"발급 내역 없음: user1@test.com / 010-1234-5678");
		}

		@PostMapping("/test/validate")
		void validate(@Valid @RequestBody TestRequest request) {
		}
	}

	record TestRequest(@NotBlank(message = "requestId 는 필수입니다.") String requestId) {
	}
}
