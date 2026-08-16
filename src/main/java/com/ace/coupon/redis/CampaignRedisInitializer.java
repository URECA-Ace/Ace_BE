package com.ace.coupon.redis;

import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.ace.coupon.entity.CouponEvent;

@Component
public class CampaignRedisInitializer {

	private final StringRedisTemplate redisTemplate;
	private final RedisScript<Long> initializeScript;
	private final CouponIssueRedisProperties properties;

	public CampaignRedisInitializer(
			StringRedisTemplate redisTemplate,
			@Qualifier("couponCampaignInitializeScript") RedisScript<Long> initializeScript,
			CouponIssueRedisProperties properties) {
		this.redisTemplate = redisTemplate;
		this.initializeScript = initializeScript;
		this.properties = properties;
	}

	public CampaignInitializationResult initialize(CouponEvent campaign) {
		if (campaign == null || campaign.getId() == null) {
			throw new IllegalArgumentException("영속화된 캠페인이 필요합니다.");
		}

		Instant openAt = campaign.getOpenAt()
				.atZone(properties.zoneId())
				.toInstant();
		Instant closeAt = campaign.getCloseAt()
				.atZone(properties.zoneId())
				.toInstant();

		return initialize(campaign.getId(), campaign.getTotalStock(), openAt, closeAt);
	}

	public CampaignInitializationResult initialize(
			Long campaignId,
			int totalStock,
			Instant openAt,
			Instant closeAt) {
		if (openAt == null || closeAt == null) {
			throw new IllegalArgumentException("캠페인 오픈 및 마감 시각이 필요합니다.");
		}

		CouponRedisKeys.CampaignKeys keys = CouponRedisKeys.campaign(campaignId);
		long expireAt = closeAt.plus(properties.retention()).toEpochMilli();
		Long code = redisTemplate.execute(
				initializeScript,
				List.of(
						keys.metadata(),
						keys.stock(),
						keys.sequence(),
						keys.requests(),
						keys.issueStream()),
				String.valueOf(totalStock),
				String.valueOf(openAt.toEpochMilli()),
				String.valueOf(closeAt.toEpochMilli()),
				String.valueOf(expireAt),
				String.valueOf(CouponRedisKeys.BITMAP_SEGMENT_BITS));

		if (code == null) {
			throw new IllegalStateException("캠페인 Redis 초기화 결과가 없습니다.");
		}
		return CampaignInitializationResult.from(code);
	}
}
