package com.ace.coupon.dto.response;

import java.time.OffsetDateTime;

import com.ace.coupon.redis.CampaignInitializationResult;

// 캠페인 Redis 초기화 결과
public record CampaignInitializationResponse(
		Long eventId,
		CampaignInitializationResult result,
		Integer totalStock,
		OffsetDateTime openAt,
		OffsetDateTime closeAt) {
}
