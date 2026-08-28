package com.ace.consistency.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ace.consistency.common.ViolationTargetType;
import com.ace.consistency.dto.response.ConsistencyViolationPageResponse;
import com.ace.consistency.dto.response.ConsistencyViolationResponse;
import com.ace.consistency.service.ConsistencyReportService;

@WebMvcTest(ConsistencyReportController.class)
class ConsistencyReportControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ConsistencyReportService service;

	@Test
	void 결과의_위반_목록을_페이징하여_조회한다() throws Exception {
		given(service.findViolations(7L, 0, 20)).willReturn(new ConsistencyViolationPageResponse(
				List.of(new ConsistencyViolationResponse(11L, ViolationTargetType.EVENT, 101L,
						Map.of("expected", 10, "actual", 9), LocalDateTime.of(2026, 8, 28, 10, 0))),
				0, 20, 1, 1, false));

		mockMvc.perform(get("/api/v1/consistency/results/7/violations"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content[0].id").value(11))
				.andExpect(jsonPath("$.data.content[0].targetType").value("EVENT"))
				.andExpect(jsonPath("$.data.content[0].targetId").value(101))
				.andExpect(jsonPath("$.data.content[0].detail.expected").value(10))
				.andExpect(jsonPath("$.data.totalElements").value(1));
	}

	@Test
	void 위반_목록의_페이지_크기를_검증한다() throws Exception {
		mockMvc.perform(get("/api/v1/consistency/results/7/violations").param("size", "101"))
				.andExpect(status().isBadRequest());
	}
}
