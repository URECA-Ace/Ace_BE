package com.ace.coupon.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.web.bind.annotation.RequestParam;

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
		given(couponEventQueryService.findRecentEvents(null, 6)).willReturn(List.of(
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

		verify(couponEventQueryService).findRecentEvents(null, 6);
	}

	@Test
	@DisplayName("상태를 전달하면 해당 상태의 최근 발급 회차만 조회한다")
	void findsRecentCouponEventsByStatus() throws Exception {
		given(couponEventQueryService.findRecentEvents(CouponEventStatus.OPEN, 10)).willReturn(List.of());

		mockMvc.perform(get("/api/v1/coupons/events/recent")
				.param("status", "OPEN")
				.param("size", "10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(0));

		verify(couponEventQueryService).findRecentEvents(CouponEventStatus.OPEN, 10);
	}

	@Test
	@DisplayName("기존 최근 회차 경로도 하위 호환을 위해 유지한다")
	void supportsLegacyRecentEventsPath() throws Exception {
		given(couponEventQueryService.findRecentEvents(null, 6)).willReturn(List.of());

		mockMvc.perform(get("/api/v1/events/recent"))
				.andExpect(status().isOk());

		verify(couponEventQueryService).findRecentEvents(null, 6);
	}

	@Test
	@DisplayName("컴파일러 파라미터 메타데이터 없이도 최근 회차 요청 파라미터 이름을 해석한다")
	void declaresRecentEventRequestParameterNames() throws Exception {
		var method = CouponEventQueryController.class.getDeclaredMethod(
				"findRecentEvents", CouponEventStatus.class, int.class);

		assertThat(method.getParameters()[0].getAnnotation(RequestParam.class).name())
				.isEqualTo("status");
		assertThat(method.getParameters()[1].getAnnotation(RequestParam.class).name())
				.isEqualTo("size");
	}
}
