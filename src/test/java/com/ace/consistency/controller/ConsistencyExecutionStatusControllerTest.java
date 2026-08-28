package com.ace.consistency.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ace.consistency.dto.response.ConsistencyJobExecutionResponse;
import com.ace.consistency.service.ConsistencyVerificationService;

@WebMvcTest(ConsistencyExecutionController.class)
class ConsistencyExecutionStatusControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ConsistencyVerificationService service;

	@Test
	void 비동기_검증_실행_상태를_조회한다() throws Exception {
		LocalDateTime startedAt = LocalDateTime.of(2026, 8, 28, 10, 0);
		given(service.findExecution(55L)).willReturn(new ConsistencyJobExecutionResponse(
				55L, BatchStatus.COMPLETED, startedAt, startedAt, startedAt.plusSeconds(3),
				startedAt.plusSeconds(3), "COMPLETED", null));

		mockMvc.perform(get("/api/v1/consistency/verifications/55"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.jobExecutionId").value(55))
				.andExpect(jsonPath("$.data.status").value("COMPLETED"))
				.andExpect(jsonPath("$.data.exitCode").value("COMPLETED"))
				.andExpect(jsonPath("$.data.errorMessage").doesNotExist());
	}
}
