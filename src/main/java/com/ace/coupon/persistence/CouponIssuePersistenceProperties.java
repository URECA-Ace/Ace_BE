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
		Duration refreshInterval,
		Boolean campaignCache) {

	private static final PersistenceMode DEFAULT_MODE = PersistenceMode.SYNC;
	private static final String DEFAULT_CONSUMER_GROUP = "issue-persist";
	private static final int DEFAULT_BATCH_SIZE = 100;
	private static final Duration DEFAULT_BLOCK_TIMEOUT = Duration.ofSeconds(2);
	private static final Duration DEFAULT_CLAIM_MIN_IDLE = Duration.ofSeconds(30);
	private static final int DEFAULT_MAX_DELIVERY_ATTEMPTS = 3;
	private static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofSeconds(10);
	private static final boolean DEFAULT_CAMPAIGN_CACHE = true;

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
		// 측정용 스위치
		// 캐시 효과를 수치로 남기려고 둔 것이라 결과를 문서화한 뒤 지울 예정
		campaignCache = campaignCache == null ? DEFAULT_CAMPAIGN_CACHE : campaignCache;

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
