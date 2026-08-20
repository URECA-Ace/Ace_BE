package com.ace.coupon.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.ace.config.PersistenceConfig;

// 설정 키 이름이 실제로 바인딩되는지 확인
class CouponIssuePersistencePropertiesBindingTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
			.withUserConfiguration(PersistenceConfig.class);

	@Test
	@DisplayName("설정이 없어도 기본값으로 기동한다")
	void bindsDefaults() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(CouponIssuePersistenceProperties.class).mode())
					.isEqualTo(PersistenceMode.SYNC);
		});
	}

	@Test
	@DisplayName("application.properties 키가 그대로 바인딩된다")
	void bindsAllKeys() {
		runner.withPropertyValues(
						"coupon.issue.persistence.mode=RELAY",
						"coupon.issue.persistence.consumer-group=relay-1",
						"coupon.issue.persistence.batch-size=50",
						"coupon.issue.persistence.block-timeout=5s",
						"coupon.issue.persistence.claim-min-idle=1m",
						"coupon.issue.persistence.max-delivery-attempts=5",
						"coupon.issue.persistence.refresh-interval=30s")
				.run(context -> {
					CouponIssuePersistenceProperties properties =
							context.getBean(CouponIssuePersistenceProperties.class);

					assertThat(properties.mode()).isEqualTo(PersistenceMode.RELAY);
					assertThat(properties.relay()).isTrue();
					assertThat(properties.consumerGroup()).isEqualTo("relay-1");
					assertThat(properties.batchSize()).isEqualTo(50);
					assertThat(properties.blockTimeout()).isEqualTo(Duration.ofSeconds(5));
					assertThat(properties.claimMinIdle()).isEqualTo(Duration.ofMinutes(1));
					assertThat(properties.maxDeliveryAttempts()).isEqualTo(5);
					assertThat(properties.refreshInterval()).isEqualTo(Duration.ofSeconds(30));
				});
	}

	@Test
	@DisplayName("범위를 벗어난 값이면 기동에 실패한다")
	void failsOnInvalidValue() {
		runner.withPropertyValues("coupon.issue.persistence.batch-size=0")
				.run(context -> assertThat(context).hasFailed());
	}
}
