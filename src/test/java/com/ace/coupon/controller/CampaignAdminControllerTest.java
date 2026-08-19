package com.ace.coupon.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.response.CampaignInitializationResponse;
import com.ace.coupon.redis.CampaignInitializationResult;
import com.ace.coupon.service.CampaignAdminService;

class CampaignAdminControllerTest {

	@Nested
	@WebMvcTest(CampaignAdminController.class)
	@TestPropertySource(properties = "coupon.issue.admin.enabled=true")
	@DisplayName("활성화된 경우")
	class Enabled {

		@Autowired
		private MockMvc mockMvc;

		@MockitoBean
		private CampaignAdminService campaignAdminService;

		@Test
		@DisplayName("초기화 결과와 Redis 에 들어간 값을 함께 돌려준다")
		void initializes() throws Exception {
			given(campaignAdminService.initialize(1L)).willReturn(new CampaignInitializationResponse(
					1L,
					CampaignInitializationResult.INITIALIZED,
					10_000,
					OffsetDateTime.parse("2026-08-19T12:00:00+09:00"),
					OffsetDateTime.parse("2026-08-19T13:00:00+09:00")));

			mockMvc.perform(post("/internal/campaigns/1/init"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.result").value("success"))
					.andExpect(jsonPath("$.data.eventId").value(1))
					.andExpect(jsonPath("$.data.result").value("INITIALIZED"))
					.andExpect(jsonPath("$.data.totalStock").value(10000));
		}

		@Test
		@DisplayName("없는 회차는 404와 에러 코드를 돌려준다")
		void reportsMissingEvent() throws Exception {
			given(campaignAdminService.initialize(99L))
					.willThrow(new CouponException(ErrorCode.EVENT_NOT_FOUND));

			mockMvc.perform(post("/internal/campaigns/99/init"))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.error.code").value("EVENT_NOT_FOUND"));
		}
	}

	@Nested
	@DisplayName("노출 제어")
	class Exposure {

		private final ApplicationContextRunner runner = new ApplicationContextRunner()
				.withBean(CampaignAdminService.class, () -> org.mockito.Mockito.mock(CampaignAdminService.class))
				.withUserConfiguration(CampaignAdminController.class);

		@Test
		@DisplayName("기본값에서는 빈이 만들어지지 않는다 - Redis 상태를 바꾸는 엔드포인트다")
		void hiddenByDefault() {
			runner.run(context -> assertThat(context).doesNotHaveBean(CampaignAdminController.class));
		}

		@Test
		@DisplayName("명시적으로 켜야 노출된다")
		void exposedWhenEnabled() {
			runner.withPropertyValues("coupon.issue.admin.enabled=true")
					.run(context -> assertThat(context).hasSingleBean(CampaignAdminController.class));
		}
	}
}
