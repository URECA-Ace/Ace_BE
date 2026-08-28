package com.ace.coupon.persistence;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 저장 경로 설정
@ConfigurationProperties(prefix = "coupon.issue.persistence")
public record CouponIssuePersistenceProperties(
		PersistenceMode mode,
		String consumerGroup,
		Integer batchSize,
		Duration blockTimeout,
		Duration claimMinIdle,
		Integer maxDeliveryAttempts,
		Duration refreshInterval) {

	private static final PersistenceMode DEFAULT_MODE = PersistenceMode.RELAY;
	private static final String DEFAULT_CONSUMER_GROUP = "issue-persist";
	private static final int DEFAULT_BATCH_SIZE = 100;
	// spring.data.redis.timeout(2s) 보다 짧아야 한다
	// 같으면 Stream 이 빌 때마다 BLOCK 이 그 시간을 다 쓰고 Lettuce 가 커맨드 타임아웃을 낸다
	private static final Duration DEFAULT_BLOCK_TIMEOUT = Duration.ofSeconds(1);
	private static final Duration DEFAULT_CLAIM_MIN_IDLE = Duration.ofSeconds(30);
	private static final int DEFAULT_MAX_DELIVERY_ATTEMPTS = 3;
	private static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofSeconds(10);

	public CouponIssuePersistenceProperties {
		mode = mode == null ? DEFAULT_MODE : mode;
		consumerGroup = consumerGroup == null || consumerGroup.isBlank()
				? DEFAULT_CONSUMER_GROUP
				: consumerGroup;
		batchSize = batchSize == null ? DEFAULT_BATCH_SIZE : batchSize;
		blockTimeout = blockTimeout == null ? DEFAULT_BLOCK_TIMEOUT : blockTimeout;
		claimMinIdle = claimMinIdle == null ? DEFAULT_CLAIM_MIN_IDLE : claimMinIdle;
		maxDeliveryAttempts = maxDeliveryAttempts == null
				? DEFAULT_MAX_DELIVERY_ATTEMPTS
				: maxDeliveryAttempts;
		refreshInterval = refreshInterval == null ? DEFAULT_REFRESH_INTERVAL : refreshInterval;

		if (batchSize <= 0) {
			throw new IllegalArgumentException("batchSize는 양수여야 합니다.");
		}
		if (blockTimeout.isNegative() || blockTimeout.isZero()) {
			throw new IllegalArgumentException("blockTimeout은 양수여야 합니다.");
		}
		if (claimMinIdle.isNegative() || claimMinIdle.isZero()) {
			throw new IllegalArgumentException("claimMinIdle은 양수여야 합니다.");
		}
		if (maxDeliveryAttempts <= 0) {
			throw new IllegalArgumentException("maxDeliveryAttempts는 양수여야 합니다.");
		}
		if (refreshInterval.isNegative() || refreshInterval.isZero()) {
			throw new IllegalArgumentException("refreshInterval은 양수여야 합니다.");
		}
	}

	public boolean relay() {
		return mode == PersistenceMode.RELAY;
	}
}
