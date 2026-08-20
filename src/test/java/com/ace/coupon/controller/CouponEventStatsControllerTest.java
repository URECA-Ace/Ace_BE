package com.ace.coupon.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.response.CouponEventStatsResponse;
import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.service.CouponEventStatsService;

@WebMvcTest(CouponEventStatsController.class)
class CouponEventStatsControllerTest {

	private static final Long EVENT_ID = 19L;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CouponEventStatsService couponEventStatsService;

	@Test
	@DisplayName("쿠폰 발급 현황 조회에 성공하면 Redis 실시간 스냅샷을 반환한다")
	void findsRealtimeIssuanceStats() throws Exception {
		OffsetDateTime observedAt = OffsetDateTime.parse("2026-08-18T17:30:00+09:00");
		given(couponEventStatsService.findStats(EVENT_ID)).willReturn(
				new CouponEventStatsResponse(
						EVENT_ID,
						10_000L,
						8_271L,
						1_729L,
						CouponEventStatus.OPEN,
						observedAt));

		mockMvc.perform(get("/api/v1/events/{eventId}/issuance-stats", EVENT_ID))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.result").value("success"))
				.andExpect(jsonPath("$.data.eventId").value(EVENT_ID))
				.andExpect(jsonPath("$.data.totalStock").value(10_000))
				.andExpect(jsonPath("$.data.allocatedQuantity").value(8_271))
				.andExpect(jsonPath("$.data.remainingStock").value(1_729))
				.andExpect(jsonPath("$.data.status").value("OPEN"))
				.andExpect(jsonPath("$.data.observedAt").value("2026-08-18T17:30:00+09:00"));

		verify(couponEventStatsService).findStats(EVENT_ID);
	}

	@Test
	@DisplayName("존재하지 않는 캠페인의 발급 현황은 404를 반환한다")
	void returnsNotFoundForMissingEvent() throws Exception {
		given(couponEventStatsService.findStats(EVENT_ID))
				.willThrow(new CouponException(ErrorCode.EVENT_NOT_FOUND));

		mockMvc.perform(get("/api/v1/events/{eventId}/issuance-stats", EVENT_ID))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("EVENT_NOT_FOUND"));
	}

	@Test
	@DisplayName("Redis 발급 현황을 조회할 수 없으면 503을 반환한다")
	void returnsServiceUnavailableWhenStatsCannotBeRead() throws Exception {
		given(couponEventStatsService.findStats(EVENT_ID))
				.willThrow(new CouponException(ErrorCode.EVENT_STATS_TEMPORARILY_UNAVAILABLE));

		mockMvc.perform(get("/api/v1/events/{eventId}/issuance-stats", EVENT_ID))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code")
						.value("EVENT_STATS_TEMPORARILY_UNAVAILABLE"));
	}

	@Test
	@DisplayName("eventId가 양수가 아니면 400을 반환한다")
	void rejectsNonPositiveEventId() throws Exception {
		mockMvc.perform(get("/api/v1/events/{eventId}/issuance-stats", 0))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}
}
