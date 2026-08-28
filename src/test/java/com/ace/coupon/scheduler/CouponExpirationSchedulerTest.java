package com.ace.coupon.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.ace.coupon.service.CouponExpirationService;
import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class CouponExpirationSchedulerTest {

	@Configuration
	static class MockConfig {
		@Bean
		public CouponExpirationService couponExpirationService() {
			return Mockito.mock(CouponExpirationService.class);
		}
	}

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(MockConfig.class)
			.withConfiguration(AutoConfigurations.of(CouponExpirationScheduler.class));

	@Test
	@DisplayName("기본 설정에서는 스케줄러 빈이 등록되지 않는다 (기본 OFF)")
	void schedulerIsOffByDefault() {
		contextRunner.run(context -> {
			assertThat(context).doesNotHaveBean(CouponExpirationScheduler.class);
		});
	}

	@Test
	@DisplayName("설정에서 enabled=true일 때 스케줄러 빈이 등록된다")
	void schedulerIsOnWhenEnabled() {
		contextRunner
				.withPropertyValues("coupon.expiration.scheduler.enabled=true")
				.run(context -> {
					assertThat(context).hasSingleBean(CouponExpirationScheduler.class);
				});
	}
}
