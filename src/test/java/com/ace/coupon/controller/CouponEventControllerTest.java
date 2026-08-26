package com.ace.coupon.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ace.coupon.dto.request.CouponEventCreateRequest;
import com.ace.coupon.dto.response.CouponEventCreateResponse;
import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.service.CouponEventCreationService;

@WebMvcTest(CouponEventController.class)
class CouponEventControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CouponEventCreationService couponEventCreationService;

	@Test
	@DisplayName("쿠폰 캠페인을 생성하고 Redis 초기화를 마치면 201을 반환한다")
	void createsCouponEvent() throws Exception {
		OffsetDateTime openAt = OffsetDateTime.parse("2099-08-19T10:00:00+09:00");
		OffsetDateTime closeAt = OffsetDateTime.parse("2099-08-19T23:59:59+09:00");
		given(couponEventCreationService.create(any(), any())).willReturn(
				new CouponEventCreateResponse(
						24L, 1L, 24, 10_000, 10_000, 1,
						CouponEventStatus.SCHEDULED, openAt, closeAt));

		mockMvc.perform(post("/api/v1/coupons/{couponId}/events", 1L)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "round": 24,
						  "totalStock": 10000,
						  "openAt": "2099-08-19T10:00:00+09:00",
						  "closeAt": "2099-08-19T23:59:59+09:00"
						}
						"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.result").value("success"))
				.andExpect(jsonPath("$.data.eventId").value(24))
				.andExpect(jsonPath("$.data.couponId").value(1))
				.andExpect(jsonPath("$.data.totalStock").value(10_000))
				.andExpect(jsonPath("$.data.remainingStock").value(10_000))
				.andExpect(jsonPath("$.data.perUserLimit").value(1))
				.andExpect(jsonPath("$.data.status").value("SCHEDULED"));

		verify(couponEventCreationService).create(any(Long.class), any(CouponEventCreateRequest.class));
	}

	@Test
	@DisplayName("회차를 생략하면 서버 자동 회차 배정으로 캠페인을 생성한다")
	void createsCouponEventWithAutoRound() throws Exception {
		OffsetDateTime openAt = OffsetDateTime.parse("2099-08-19T10:00:00+09:00");
		OffsetDateTime closeAt = OffsetDateTime.parse("2099-08-19T23:59:59+09:00");
		given(couponEventCreationService.create(any(), any())).willReturn(
				new CouponEventCreateResponse(
						25L, 1L, 25, 10_000, 10_000, 1,
						CouponEventStatus.SCHEDULED, openAt, closeAt));

		mockMvc.perform(post("/api/v1/coupons/{couponId}/events", 1L)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "totalStock": 10000,
						  "openAt": "2099-08-19T10:00:00+09:00",
						  "closeAt": "2099-08-19T23:59:59+09:00"
						}
						"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.round").value(25));
	}

	@Test
	@DisplayName("캠페인 재고가 0이면 400을 반환한다")
	void rejectsZeroStock() throws Exception {
		mockMvc.perform(post("/api/v1/coupons/{couponId}/events", 1L)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "round": 24,
						  "totalStock": 0,
						  "openAt": "2099-08-19T10:00:00+09:00",
						  "closeAt": "2099-08-19T23:59:59+09:00"
						}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.error.message").value("totalStock은 0보다 커야 합니다."));
	}
}
