package com.ace.coupon.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ace.coupon.dto.request.CouponCreateRequest;
import com.ace.coupon.dto.response.CouponCreateResponse;
import com.ace.coupon.dto.response.CouponSummaryResponse;
import com.ace.coupon.service.CouponCreationService;
import com.ace.coupon.service.CouponQueryService;

import static org.mockito.BDDMockito.given;

@WebMvcTest(CouponController.class)
class CouponControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CouponCreationService couponCreationService;

	@MockitoBean
	private CouponQueryService couponQueryService;

	@Test
	@DisplayName("검색어가 없으면 최근 쿠폰 목록을 반환한다")
	void findsRecentCoupons() throws Exception {
		given(couponQueryService.findCoupons(null)).willReturn(List.of(
				new CouponSummaryResponse(
						51L, "U+ 데이터 하루 무제한 쿠폰", "DATA_UNLIMITED", 0L, 24,
						LocalDateTime.of(2026, 8, 21, 15, 0))));

		mockMvc.perform(get("/api/v1/coupons"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result").value("success"))
				.andExpect(jsonPath("$.data[0].couponId").value(51))
				.andExpect(jsonPath("$.data[0].couponName").value("U+ 데이터 하루 무제한 쿠폰"));

		verify(couponQueryService).findCoupons(null);
	}

	@Test
	@DisplayName("제목 검색어를 전달해 전체 쿠폰에서 검색한다")
	void searchesCouponsByTitle() throws Exception {
		given(couponQueryService.findCoupons("무제한")).willReturn(List.of(
				new CouponSummaryResponse(
						51L, "U+ 데이터 하루 무제한 쿠폰", "DATA_UNLIMITED", 0L, 24,
						LocalDateTime.of(2026, 8, 21, 15, 0))));

		mockMvc.perform(get("/api/v1/coupons").param("keyword", "무제한"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].couponName").value("U+ 데이터 하루 무제한 쿠폰"));

		verify(couponQueryService).findCoupons("무제한");
	}

	@Test
	@DisplayName("쿠폰 상품 정보를 생성하면 201과 식별자를 반환한다")
	void createsCoupon() throws Exception {
		given(couponCreationService.create(any())).willReturn(new CouponCreateResponse(
				51L, "U+ 데이터 하루 무제한 쿠폰", "DATA_UNLIMITED", 0L, 24,
				LocalDateTime.of(2026, 8, 21, 15, 0)));

		mockMvc.perform(post("/api/v1/coupons")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "couponName": "U+ 데이터 하루 무제한 쿠폰",
						  "type": "DATA_UNLIMITED",
						  "value": 0,
						  "validHours": 24
						}
						"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.result").value("success"))
				.andExpect(jsonPath("$.data.couponId").value(51))
				.andExpect(jsonPath("$.data.validHours").value(24));

		verify(couponCreationService).create(any(CouponCreateRequest.class));
	}
}
