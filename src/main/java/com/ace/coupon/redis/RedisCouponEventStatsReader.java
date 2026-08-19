package com.ace.coupon.redis;

import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.ace.coupon.enums.CouponEventStatus;

@Component
public class RedisCouponEventStatsReader {

	private static final int RESULT_FIELD_COUNT = 6;

	private final StringRedisTemplate redisTemplate;
	private final RedisScript<List> statsScript;

	public RedisCouponEventStatsReader(
			StringRedisTemplate redisTemplate,
			@Qualifier("couponEventStatsScript") RedisScript<List> statsScript) {
		this.redisTemplate = redisTemplate;
		this.statsScript = statsScript;
	}

	/**
	 * Redis의 캠페인 메타데이터, 재고, 서버 시각을 하나의 Lua 실행에서 읽는다.
	 *
	 * @return Redis에 캠페인 판정 데이터가 없으면 null
	 */
	public CouponEventStatsSnapshot read(Long campaignId) {
		CouponRedisKeys.CampaignKeys keys = CouponRedisKeys.campaign(campaignId);
		List<?> result = redisTemplate.execute(
				statsScript,
				List.of(keys.metadata(), keys.stock()));

		if (result == null || result.size() != RESULT_FIELD_COUNT) {
			throw new IllegalStateException("쿠폰 발급 현황 Lua 결과 형식이 올바르지 않습니다.");
		}

		CouponEventStatsLuaCode code = CouponEventStatsLuaCode.from(number(result.get(0)));
		if (code == CouponEventStatsLuaCode.CAMPAIGN_NOT_INITIALIZED) {
			return null;
		}
		if (code == CouponEventStatsLuaCode.CORRUPTED_STATE) {
			throw new IllegalStateException("쿠폰 발급 현황 Redis 상태가 올바르지 않습니다.");
		}

		long totalStock = number(result.get(1));
		long allocatedQuantity = number(result.get(2));
		long remainingStock = number(result.get(3));
		long statusCode = number(result.get(4));
		long observedAt = number(result.get(5));
		if (totalStock <= 0
				|| allocatedQuantity < 0
				|| remainingStock < 0
				|| allocatedQuantity + remainingStock != totalStock
				|| observedAt <= 0) {
			throw new IllegalStateException("쿠폰 발급 현황 Lua 결과 값이 올바르지 않습니다.");
		}

		return new CouponEventStatsSnapshot(
				campaignId,
				totalStock,
				allocatedQuantity,
				remainingStock,
				status(statusCode),
				Instant.ofEpochMilli(observedAt));
	}

	private CouponEventStatus status(long value) {
		return switch ((int) value) {
			case 0 -> CouponEventStatus.SCHEDULED;
			case 1 -> CouponEventStatus.OPEN;
			case 2 -> CouponEventStatus.SOLD_OUT;
			case 3 -> CouponEventStatus.CLOSED;
			default -> throw new IllegalStateException("정의되지 않은 쿠폰 캠페인 현황 상태: " + value);
		};
	}

	private long number(Object value) {
		try {
			if (value instanceof Number number) {
				return number.longValue();
			}
			return Long.parseLong(String.valueOf(value));
		} catch (NumberFormatException exception) {
			throw new IllegalStateException("쿠폰 발급 현황 Lua 숫자 결과가 올바르지 않습니다.", exception);
		}
	}
}
