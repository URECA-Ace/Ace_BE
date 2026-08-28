package com.ace.coupon.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CouponIssuePersistencePropertiesTest {

	@Test
	@DisplayName("설정이 없으면 기본값으로 채운다")
	void fillsDefaults() {
		CouponIssuePersistenceProperties properties =
				new CouponIssuePersistenceProperties(null, null, null, null, null, null, null);

		assertThat(properties.mode()).isEqualTo(PersistenceMode.RELAY);
		assertThat(properties.consumerGroup()).isEqualTo("issue-persist");
		assertThat(properties.batchSize()).isEqualTo(100);
		// spring.data.redis.timeout(2s) 보다 짧아야 커맨드 타임아웃이 안 난다
		assertThat(properties.blockTimeout()).isEqualTo(Duration.ofSeconds(1));
		assertThat(properties.claimMinIdle()).isEqualTo(Duration.ofSeconds(30));
		assertThat(properties.maxDeliveryAttempts()).isEqualTo(3);
		assertThat(properties.refreshInterval()).isEqualTo(Duration.ofSeconds(10));
		assertThat(properties.relay()).isTrue();
	}

	@Test
	@DisplayName("지정한 값을 그대로 쓴다")
	void keepsGivenValues() {
		CouponIssuePersistenceProperties properties = new CouponIssuePersistenceProperties(
				PersistenceMode.RELAY, "relay-1", 50,
				Duration.ofSeconds(5), Duration.ofMinutes(1), 5, Duration.ofSeconds(30));

		assertThat(properties.mode()).isEqualTo(PersistenceMode.RELAY);
		assertThat(properties.consumerGroup()).isEqualTo("relay-1");
		assertThat(properties.relay()).isTrue();
	}

	@Test
	@DisplayName("빈 컨슈머 그룹은 기본값으로 되돌린다")
	void fallsBackOnBlankConsumerGroup() {
		CouponIssuePersistenceProperties properties =
				new CouponIssuePersistenceProperties(null, "  ", null, null, null, null, null);

		assertThat(properties.consumerGroup()).isEqualTo("issue-persist");
	}

	@Test
	@DisplayName("batchSize가 0 이하면 거부한다")
	void rejectsNonPositiveBatchSize() {
		assertThatThrownBy(() ->
				new CouponIssuePersistenceProperties(null, null, -1, null, null, null, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("batchSize");
	}

	@Test
	@DisplayName("blockTimeout이 0이면 거부한다")
	void rejectsZeroBlockTimeout() {
		assertThatThrownBy(() ->
				new CouponIssuePersistenceProperties(null, null, null, Duration.ZERO, null, null, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("blockTimeout");
	}

	@Test
	@DisplayName("claimMinIdle이 음수면 거부한다")
	void rejectsNegativeClaimMinIdle() {
		assertThatThrownBy(() -> new CouponIssuePersistenceProperties(
				null, null, null, null, Duration.ofSeconds(-1), null, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("claimMinIdle");
	}

	@Test
	@DisplayName("maxDeliveryAttempts가 0 이하면 거부한다")
	void rejectsNonPositiveMaxDeliveryAttempts() {
		assertThatThrownBy(() ->
				new CouponIssuePersistenceProperties(null, null, null, null, null, 0, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("maxDeliveryAttempts");
	}
}
