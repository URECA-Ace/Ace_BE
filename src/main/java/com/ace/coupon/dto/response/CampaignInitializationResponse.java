package com.ace.coupon.dto.response;

import java.time.OffsetDateTime;

import com.ace.coupon.redis.CampaignInitializationResult;

/**
 * 캠페인 Redis 초기화 결과.
 *
 * <p>MySQL 회차에서 읽은 값을 그대로 담는다. 수동 초기화가 어긋났을 때
 * 응답만 보고 "Redis 에 어떤 값이 들어갔는지" 확인할 수 있어야 한다.
 */
public record CampaignInitializationResponse(
		Long eventId,
		CampaignInitializationResult result,
		Integer totalStock,
		OffsetDateTime openAt,
		OffsetDateTime closeAt) {
}
