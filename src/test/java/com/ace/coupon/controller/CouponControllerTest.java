package com.ace.coupon.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestParam;

import com.ace.coupon.dto.request.CouponCreateRequest;
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
		given(couponQueryService.findCoupons(null, 6)).willReturn(List.of(
				new CouponSummaryResponse(
						51L, "U+ 데이터 하루 무제한 쿠폰", "DATA_UNLIMITED", 0L, 24,
						OffsetDateTime.parse("2026-08-21T15:00:00+09:00"))));

		mockMvc.perform(get("/api/v1/coupons"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result").value("success"))
				.andExpect(jsonPath("$.data[0].couponId").value(51))
				.andExpect(jsonPath("$.data[0].couponName").value("U+ 데이터 하루 무제한 쿠폰"));

		verify(couponQueryService).findCoupons(null, 6);
	}

	@Test
	@DisplayName("제목 검색어를 전달해 전체 쿠폰에서 검색한다")
	void searchesCouponsByTitle() throws Exception {
		given(couponQueryService.findCoupons("무제한", 10)).willReturn(List.of(
				new CouponSummaryResponse(
						51L, "U+ 데이터 하루 무제한 쿠폰", "DATA_UNLIMITED", 0L, 24,
						OffsetDateTime.parse("2026-08-21T15:00:00+09:00"))));

		mockMvc.perform(get("/api/v1/coupons")
				.param("keyword", "무제한")
				.param("size", "10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].couponName").value("U+ 데이터 하루 무제한 쿠폰"));

		verify(couponQueryService).findCoupons("무제한", 10);
	}

	@Test
	@DisplayName("쿠폰 상품 정보를 생성하면 201과 식별자를 반환한다")
	void createsCoupon() throws Exception {
		given(couponCreationService.create(any())).willReturn(new CouponSummaryResponse(
				51L, "U+ 데이터 하루 무제한 쿠폰", "DATA_UNLIMITED", 0L, 24,
				OffsetDateTime.parse("2026-08-21T15:00:00+09:00")));

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

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidCouponRequests")
	@DisplayName("쿠폰 생성 필수 값과 범위 검증 실패는 400을 반환한다")
	void rejectsInvalidCouponCreateRequests(String caseName, String requestBody) throws Exception {
		mockMvc.perform(post("/api/v1/coupons")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("허용하지 않는 쿠폰 종류는 400을 반환한다")
	void rejectsUnknownCouponType() throws Exception {
		mockMvc.perform(post("/api/v1/coupons")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "couponName": "U+ 데이터 하루 무제한 쿠폰",
						  "type": "DATA_UNLIMITD",
						  "value": 0,
						  "validHours": 24
						}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
	}

	@Test
	@DisplayName("조회 size가 상한을 넘으면 400을 반환한다")
	void rejectsOversizedCouponQuery() throws Exception {
		mockMvc.perform(get("/api/v1/coupons").param("size", "51"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("컴파일러 파라미터 메타데이터 없이도 쿠폰 조회 요청 파라미터 이름을 해석한다")
	void declaresCouponQueryRequestParameterNames() throws Exception {
		var method = CouponController.class.getDeclaredMethod(
				"findCoupons", String.class, int.class);

		assertThat(method.getParameters()[0].getAnnotation(RequestParam.class).name())
				.isEqualTo("keyword");
		assertThat(method.getParameters()[1].getAnnotation(RequestParam.class).name())
				.isEqualTo("size");
	}

	private static Stream<Arguments> invalidCouponRequests() {
		return Stream.of(
				Arguments.of("couponName 누락", """
						{"type":"DATA_UNLIMITED","value":0,"validHours":24}
						"""),
				Arguments.of("value 음수", """
						{"couponName":"쿠폰","type":"DATA_UNLIMITED","value":-1,"validHours":24}
						"""),
				Arguments.of("validHours 0", """
						{"couponName":"쿠폰","type":"DATA_UNLIMITED","value":0,"validHours":0}
						"""));
	}
}
