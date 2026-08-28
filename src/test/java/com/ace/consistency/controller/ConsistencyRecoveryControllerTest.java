package com.ace.consistency.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.ace.consistency.recovery.controller.ConsistencyRecoveryController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import static org.mockito.ArgumentMatchers.any;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ace.consistency.recovery.ConsistencyRecoveryDispatcher;
import com.ace.consistency.recovery.RecoveryResult;
import com.ace.consistency.recovery.enums.RecoveryAction;
import com.ace.consistency.recovery.enums.RecoveryResultStatus;
import com.ace.consistency.recovery.repository.RecoveryResultRepository;

// 컨트롤러가 @ConditionalOnProperty 로 묶여 있어, 켜 주지 않으면 빈이 등록되지 않아 전 요청이 404가 된다
@WebMvcTest(ConsistencyRecoveryController.class)
@TestPropertySource(properties = "consistency.recovery.admin.enabled=true")
@WebMvcTest(value = ConsistencyRecoveryController.class,
		properties = "consistency.recovery.admin.enabled=true")
class ConsistencyRecoveryControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ConsistencyRecoveryDispatcher dispatcher;

	@MockitoBean
	private RecoveryResultRepository recoveryResultRepository;

	@Test
	void 유효한_액션이면_복구를_실행하고_결과_목록을_반환한다() throws Exception {
		RecoveryResult result = RecoveryResult.from(1L,
				com.ace.consistency.recovery.RecoveryOutcome.success(
						com.ace.consistency.common.Scope.ofEvent(1L),
						Map.of("issue_id", 4), "복구완료"),
				LocalDateTime.now());
		given(dispatcher.recover(eq(1L), eq(RecoveryAction.DEFAULT))).willReturn(List.of(result));

		mockMvc.perform(post("/internal/consistency/results/1/recover")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"action\":\"DEFAULT\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result").value("success"))
				.andExpect(jsonPath("$.data[0].message").value("복구완료"))
				.andExpect(jsonPath("$.data[0].status").value(RecoveryResultStatus.SUCCESS.name()));
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

	@Test
	void 프론트용_경로에서_복구_방법을_조회한다() throws Exception {
		given(dispatcher.availableActions(1L)).willReturn(List.of(RecoveryAction.STOCK_RECONCILE_COUNTER));

		mockMvc.perform(get("/api/v1/consistency/results/1/recovery-methods"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].action").value("STOCK_RECONCILE_COUNTER"))
				.andExpect(jsonPath("$.data[0].label").value("재고 카운터 재계산"));
	}

	@Test
	void 최신순_복구_이력을_페이지로_조회한다() throws Exception {
		RecoveryResult result = RecoveryResult.from(1L,
				com.ace.consistency.recovery.RecoveryOutcome.success(
						com.ace.consistency.common.Scope.ofEvent(1L), Map.of(), "복구완료"),
				LocalDateTime.now());
		given(recoveryResultRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class)))
				.willReturn(new PageImpl<>(List.of(result)));

		mockMvc.perform(get("/api/v1/consistency/recoveries?page=0&size=10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content[0].verificationResultId").value(1))
				.andExpect(jsonPath("$.data.totalElements").value(1))
				.andExpect(jsonPath("$.data.hasNext").value(false));
	}
}
