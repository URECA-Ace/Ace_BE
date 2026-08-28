package com.ace.coupon.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ace.coupon.dto.response.CouponIssuanceLogItemResponse;
import com.ace.coupon.dto.response.CouponIssuanceLogResponse;
import com.ace.coupon.service.CouponIssuanceLogService;

@WebMvcTest(CouponIssuanceLogController.class)
class CouponIssuanceLogControllerTest {

	private static final Long EVENT_ID = 60L;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CouponIssuanceLogService couponIssuanceLogService;

	@Test
	@DisplayName("커서 다음의 DB 확정 발급 로그를 캐시 없이 반환한다")
	void findsConfirmedIssuanceLogs() throws Exception {
		given(couponIssuanceLogService.findLogs(EVENT_ID, 20, 200)).willReturn(
				new CouponIssuanceLogResponse(
						EVENT_ID,
						List.of(new CouponIssuanceLogItemResponse(
								101L,
								"홍*동",
								"hon****@example.com",
								"010-****-5678",
								21L,
								"ISSUED",
								OffsetDateTime.parse("2026-08-27T10:00:00+09:00"),
								OffsetDateTime.parse("2026-08-27T10:00:01+09:00"))),
						21L,
						false));

		mockMvc.perform(get("/api/v1/events/{eventId}/issuance-logs", EVENT_ID)
					.queryParam("afterSequence", "20")
					.queryParam("size", "200"))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.data.eventId").value(EVENT_ID))
				.andExpect(jsonPath("$.data.logs[0].userId").value(101))
				.andExpect(jsonPath("$.data.logs[0].maskedUserName").value("홍*동"))
				.andExpect(jsonPath("$.data.logs[0].maskedUserEmail")
						.value("hon****@example.com"))
				.andExpect(jsonPath("$.data.logs[0].maskedUserPhone")
						.value("010-****-5678"))
				.andExpect(jsonPath("$.data.logs[0].issueSequence").value(21))
				.andExpect(jsonPath("$.data.logs[0].status").value("ISSUED"))
				.andExpect(jsonPath("$.data.logs[0].persistedAt")
						.value("2026-08-27T10:00:01+09:00"))
				.andExpect(jsonPath("$.data.nextSequence").value(21))
				.andExpect(jsonPath("$.data.hasMore").value(false));

		verify(couponIssuanceLogService).findLogs(EVENT_ID, 20, 200);
	}

	@Test
	@DisplayName("허용 범위를 벗어난 커서와 조회 크기는 400을 반환한다")
	void rejectsInvalidCursorAndSize() throws Exception {
		mockMvc.perform(get("/api/v1/events/{eventId}/issuance-logs", EVENT_ID)
					.queryParam("afterSequence", "-1")
					.queryParam("size", "501"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}
}
