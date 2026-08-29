package com.ace.coupon.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ace.coupon.dto.response.IssueFailurePageResponse;
import com.ace.coupon.persistence.failure.IssueFailureStage;
import com.ace.coupon.persistence.failure.IssueFailureStatus;
import com.ace.coupon.service.IssueFailureAdminService;
import com.ace.coupon.service.IssueFailureQueryService;

@WebMvcTest(IssueFailureController.class)
class IssueFailureControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private IssueFailureQueryService queryService;

	@MockitoBean
	private IssueFailureAdminService adminService;

	@Test
	@DisplayName("발급 실패 목록 필터를 명시된 요청 파라미터 이름으로 바인딩한다")
	void bindsFailureListRequestParameters() throws Exception {
		given(queryService.findFailures(
				7L, IssueFailureStage.CONFIRM, IssueFailureStatus.UNRECOVERABLE,
				"request-1", 1, 20))
				.willReturn(new IssueFailurePageResponse(List.of(), 1, 20, 0, 0, false));

		mockMvc.perform(get("/api/v1/issue-failures")
				.param("eventId", "7")
				.param("stage", "CONFIRM")
				.param("status", "UNRECOVERABLE")
				.param("requestId", "request-1")
				.param("page", "1")
				.param("size", "20"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result").value("success"))
				.andExpect(jsonPath("$.data.page").value(1));

		verify(queryService).findFailures(
				7L, IssueFailureStage.CONFIRM, IssueFailureStatus.UNRECOVERABLE,
				"request-1", 1, 20);
	}
}
