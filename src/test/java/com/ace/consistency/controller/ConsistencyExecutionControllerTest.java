package com.ace.consistency.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ace.consistency.common.Scope;
import com.ace.consistency.dto.request.ConsistencyVerificationRequest;
import com.ace.consistency.dto.response.ConsistencyCheckCatalogResponse;
import com.ace.consistency.dto.response.ConsistencyCheckCatalogResponse.CheckResponse;
import com.ace.consistency.dto.response.ConsistencyCheckCatalogResponse.ScopeResponse;
import com.ace.consistency.dto.response.ConsistencyVerificationResponse;
import com.ace.consistency.service.ConsistencyVerificationService;

@WebMvcTest(ConsistencyExecutionController.class)
class ConsistencyExecutionControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ConsistencyVerificationService service;

	@Test
	void Scope별_지원_검사와_라벨을_조회한다() throws Exception {
		given(service.findSupportedChecks(Scope.ScopeType.EVENT)).willReturn(
				new ConsistencyCheckCatalogResponse(
						new ScopeResponse("EVENT", "특정 이벤트"),
						List.of(new CheckResponse("StockConsistencyCheck", "재고 정합성 검사"))));

		mockMvc.perform(get("/api/v1/consistency/checks").param("scopeType", "EVENT"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.scope.name").value("EVENT"))
				.andExpect(jsonPath("$.data.scope.label").value("특정 이벤트"))
				.andExpect(jsonPath("$.data.checks[0].name").value("StockConsistencyCheck"));
	}

	@Test
	void ALL_검증은_비동기_실행_ID와_202를_반환한다() throws Exception {
		given(service.verify(any(ConsistencyVerificationRequest.class)))
				.willReturn(ConsistencyVerificationResponse.async(55L));

		mockMvc.perform(post("/api/v1/consistency/verifications")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "scope": {"type": "ALL"},
								  "checkNames": ["StockConsistencyCheck"]
								}
								"""))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.data.executionType").value("ASYNC"))
				.andExpect(jsonPath("$.data.jobExecutionId").value(55))
				.andExpect(jsonPath("$.data.results").doesNotExist());
	}

	@Test
	void 빈_검사_목록은_400으로_거부한다() throws Exception {
		mockMvc.perform(post("/api/v1/consistency/verifications")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "scope": {"type": "EVENT", "eventId": 1},
								  "checkNames": []
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}
}
