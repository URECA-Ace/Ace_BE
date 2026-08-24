package com.ace.coupon.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import com.ace.coupon.dto.response.CouponEventSummaryResponse;
import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.service.CouponEventQueryService;

@WebMvcTest(CouponEventQueryController.class)
class CouponEventQueryControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CouponEventQueryService couponEventQueryService;

	@Test
	@DisplayName("최근 발급 회차를 쿠폰 이름과 함께 반환한다")
	void findsRecentCouponEvents() throws Exception {
		given(couponEventQueryService.findRecentEvents()).willReturn(List.of(
				new CouponEventSummaryResponse(
						51L, 7L, "U+ 데이터 하루 무제한 쿠폰", 3,
						10_000, 10_000, CouponEventStatus.OPEN,
						OffsetDateTime.parse("2026-08-25T10:00:00+09:00"),
						OffsetDateTime.parse("2026-08-25T23:59:59+09:00"))));

		mockMvc.perform(get("/api/v1/events/recent"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result").value("success"))
				.andExpect(jsonPath("$.data[0].eventId").value(51))
				.andExpect(jsonPath("$.data[0].couponName").value("U+ 데이터 하루 무제한 쿠폰"))
				.andExpect(jsonPath("$.data[0].round").value(3));

		verify(couponEventQueryService).findRecentEvents();
	}
}
