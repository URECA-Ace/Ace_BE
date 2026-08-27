package com.ace.coupon.redis;

import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
public class CampaignRedisCloser {

	private static final int RESPONSE_FIELD_COUNT = 4;

	private final StringRedisTemplate redisTemplate;
	private final RedisScript<List> closeScript;
	private final RedisLuaFailureObserver failureObserver;

	public CampaignRedisCloser(
			StringRedisTemplate redisTemplate,
			@Qualifier("couponCampaignCloseScript") RedisScript<List> closeScript,
			RedisLuaFailureObserver failureObserver) {
		this.redisTemplate = redisTemplate;
		this.closeScript = closeScript;
		this.failureObserver = failureObserver;
	}

	public CampaignCloseDecision close(Long campaignId) {
		CouponRedisKeys.CampaignKeys keys = CouponRedisKeys.campaign(campaignId);
		List<?> response = redisTemplate.execute(
				closeScript,
				List.of(keys.metadata()));

		if (response == null || response.size() != RESPONSE_FIELD_COUNT) {
			throw new IllegalStateException("캠페인 Redis 마감 결과가 없습니다.");
		}

		CampaignCloseResult result = CampaignCloseResult.from(number(response.get(0)));
		Instant closedAt = Instant.ofEpochMilli(number(response.get(1)));
		RedisLuaDiagnosticStage stage = RedisLuaDiagnosticStage.from(number(response.get(2)));
		failureObserver.observe(stage, result.name(), text(response.get(3)));
		return new CampaignCloseDecision(result, closedAt);
	}

	private long number(Object value) {
		return value instanceof Number number
				? number.longValue()
				: Long.parseLong(text(value));
	}

	private String text(Object value) {
		if (value instanceof byte[] bytes) {
			return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
		}
		return String.valueOf(value);
	}
}
