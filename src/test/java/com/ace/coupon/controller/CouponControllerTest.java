package com.ace.coupon.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ace.coupon.dto.request.CouponCreateRequest;
import com.ace.coupon.dto.response.CouponCreateResponse;
import com.ace.coupon.service.CouponCreationService;

import static org.mockito.BDDMockito.given;

@WebMvcTest(CouponController.class)
class CouponControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CouponCreationService couponCreationService;

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
