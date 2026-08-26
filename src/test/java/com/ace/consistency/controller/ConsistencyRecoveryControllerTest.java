package com.ace.consistency.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.Map;

import com.ace.consistency.recovery.controller.ConsistencyRecoveryController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ace.consistency.recovery.ConsistencyRecoveryDispatcher;
import com.ace.consistency.recovery.RecoveryResult;
import com.ace.consistency.recovery.enums.RecoveryAction;
import com.ace.consistency.recovery.enums.RecoveryResultStatus;

@WebMvcTest(ConsistencyRecoveryController.class)
class ConsistencyRecoveryControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ConsistencyRecoveryDispatcher dispatcher;

	@Test
	void 유효한_액션이면_복구를_실행하고_결과를_반환한다() throws Exception {
		RecoveryResult result = RecoveryResult.from(1L,
				com.ace.consistency.recovery.RecoveryOutcome.success(
						com.ace.consistency.common.Scope.ofEvent(1L),
						Map.of("issue_id", 4), "복구완료"),
				LocalDateTime.now());
		given(dispatcher.recover(eq(1L), eq(RecoveryAction.DEFAULT), isNull())).willReturn(result);

		mockMvc.perform(post("/internal/consistency/results/1/recover")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"action\":\"DEFAULT\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result").value("success"))
				.andExpect(jsonPath("$.data.message").value("복구완료"))
				.andExpect(jsonPath("$.data.status").value(RecoveryResultStatus.SUCCESS.name()));
	}

	@Test
	void 존재하지_않는_액션이면_400을_반환한다() throws Exception {
		mockMvc.perform(post("/internal/consistency/results/1/recover")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"action\":\"NO_SUCH_ACTION\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.result").value("error"))
				.andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
	}
}
