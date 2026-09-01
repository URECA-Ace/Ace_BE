package com.ace.coupon.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.response.CouponEventCloseResponse;
import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.service.CouponEventCloseService;

@WebMvcTest(CouponEventCloseController.class)
@TestPropertySource(properties = "coupon.campaign.close-admin.enabled=true")
class CouponEventCloseControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CouponEventCloseService couponEventCloseService;

	@Test
	@DisplayName("OPEN 캠페인을 수동 마감한다")
	void closesOpenEvent() throws Exception {
		given(couponEventCloseService.close(51L)).willReturn(new CouponEventCloseResponse(
				51L,
				CouponEventStatus.CLOSED,
				OffsetDateTime.parse("2026-08-27T10:00:00+09:00"),
				true,
				true));

		mockMvc.perform(patch("/api/v1/events/51/close"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result").value("success"))
				.andExpect(jsonPath("$.data.eventId").value(51))
				.andExpect(jsonPath("$.data.status").value("CLOSED"))
				.andExpect(jsonPath("$.data.drained").value(true))
				.andExpect(jsonPath("$.data.closeAtAdvanced").value(true))
				.andExpect(jsonPath("$.data.closedAt").value("2026-08-27T10:00:00+09:00"));
	}

	@Test
	@DisplayName("DB 마감 시각이 당겨지지 않았으면 closeAtAdvanced=false 로 드러낸다")
	void reportsCloseAtNotAdvanced() throws Exception {
		given(couponEventCloseService.close(51L)).willReturn(new CouponEventCloseResponse(
				51L,
				CouponEventStatus.OPEN,
				OffsetDateTime.parse("2026-08-27T10:00:00+09:00"),
				false,
				false));

		mockMvc.perform(patch("/api/v1/events/51/close"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.closeAtAdvanced").value(false));
	}

	@Test
	@DisplayName("확정 대기 건이 남아 있으면 상태는 OPEN 인 채로 응답한다")
	void reportsNotDrainedEvent() throws Exception {
		given(couponEventCloseService.close(51L)).willReturn(new CouponEventCloseResponse(
				51L,
				CouponEventStatus.OPEN,
				OffsetDateTime.parse("2026-08-27T10:00:00+09:00"),
				false,
				true));

		mockMvc.perform(patch("/api/v1/events/51/close"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("OPEN"))
				.andExpect(jsonPath("$.data.drained").value(false));
	}

	@Test
	@DisplayName("마감할 수 없는 상태는 409를 반환한다")
	void rejectsInvalidState() throws Exception {
		given(couponEventCloseService.close(51L))
				.willThrow(new CouponException(ErrorCode.INVALID_STATE_TRANSITION));

		mockMvc.perform(patch("/api/v1/events/51/close"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("INVALID_STATE_TRANSITION"));
	}

	@Test
	@DisplayName("기본값에서는 수동 마감 API 빈을 등록하지 않는다")
	void hiddenByDefault() {
		closeControllerContext().run(context ->
				assertThat(context).doesNotHaveBean(CouponEventCloseController.class));
	}

	@Test
	@DisplayName("명시적으로 활성화한 경우에만 수동 마감 API 빈을 등록한다")
	void exposedWhenEnabled() {
		closeControllerContext()
				.withPropertyValues("coupon.campaign.close-admin.enabled=true")
				.run(context ->
						assertThat(context).hasSingleBean(CouponEventCloseController.class));
	}

	private ApplicationContextRunner closeControllerContext() {
		return new ApplicationContextRunner()
				.withBean(CouponEventCloseService.class,
						() -> org.mockito.Mockito.mock(CouponEventCloseService.class))
				.withUserConfiguration(CouponEventCloseController.class);
	}
}
