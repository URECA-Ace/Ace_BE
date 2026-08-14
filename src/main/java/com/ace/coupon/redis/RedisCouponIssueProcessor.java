package com.ace.coupon.redis;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.ace.coupon.enums.IssueRequestStatus;

@Component
public class RedisCouponIssueProcessor {

	private static final int DECISION_FIELD_COUNT = 4;
	private static final int COMPACT_REQUEST_STATE_FIELD_COUNT = 6;
	private static final int LEGACY_REQUEST_STATE_FIELD_COUNT = 7;

	private final StringRedisTemplate redisTemplate;
	private final RedisScript<List> issueScript;
	private final RedisScript<Long> compensateScript;

	public RedisCouponIssueProcessor(
			StringRedisTemplate redisTemplate,
			@Qualifier("couponIssueScript") RedisScript<List> issueScript,
			@Qualifier("couponIssueCompensateScript") RedisScript<Long> compensateScript) {
		this.redisTemplate = redisTemplate;
		this.issueScript = issueScript;
		this.compensateScript = compensateScript;
	}

	public CouponIssueDecision issue(Long campaignId, Long userId, UUID requestId) {
		if (requestId == null) {
			throw new IllegalArgumentException("requestId가 필요합니다.");
		}

		CouponRedisKeys.CampaignKeys keys = CouponRedisKeys.campaign(campaignId);
		CouponRedisKeys.BitmapLocation bitmap = keys.bitmap(userId);
		List<?> result = redisTemplate.execute(
				issueScript,
				List.of(
						keys.metadata(),
						keys.stock(),
						keys.sequence(),
						bitmap.key(),
						keys.requests(),
						keys.issueStream()),
				String.valueOf(userId),
				String.valueOf(bitmap.offset()),
				requestId.toString(),
				String.valueOf(campaignId));

		if (result == null || result.size() != DECISION_FIELD_COUNT) {
			throw new IllegalStateException("쿠폰 발급 Lua 결과 형식이 올바르지 않습니다.");
		}

		CouponIssueLuaCode code = CouponIssueLuaCode.from(number(result.get(0)));
		long sequence = number(result.get(1));
		long remainingStock = number(result.get(2));
		long decidedAt = number(result.get(3));
		if (code == CouponIssueLuaCode.ACCEPTED
				&& (sequence <= 0 || remainingStock < 0 || decidedAt <= 0)) {
			throw new IllegalStateException("승인된 쿠폰 발급 결과가 올바르지 않습니다.");
		}
		return new CouponIssueDecision(
				code,
				sequence < 0 ? null : sequence,
				remainingStock < 0 ? null : remainingStock,
				decidedAt <= 0 ? null : Instant.ofEpochMilli(decidedAt));
	}

	public CouponIssueRequestState findRequest(Long campaignId, UUID requestId) {
		if (requestId == null) {
			throw new IllegalArgumentException("requestId가 필요합니다.");
		}

		CouponRedisKeys.CampaignKeys keys = CouponRedisKeys.campaign(campaignId);
		Object value = redisTemplate.opsForHash().get(keys.requests(), requestId.toString());
		if (value == null) {
			return null;
		}

		try {
			String[] fields = value.toString().split("\\|", -1);
			if (fields.length != COMPACT_REQUEST_STATE_FIELD_COUNT
					&& fields.length != LEGACY_REQUEST_STATE_FIELD_COUNT) {
				throw new IllegalStateException("쿠폰 요청 상태 필드 수가 올바르지 않습니다.");
			}

			long sequence = Long.parseLong(fields[3]);
			long remainingStock = Long.parseLong(fields[4]);
			long decidedAt = Long.parseLong(fields[5]);
			if (decidedAt <= 0) {
				throw new IllegalStateException("쿠폰 요청 판정 시각이 올바르지 않습니다.");
			}
			return new CouponIssueRequestState(
					requestId,
					campaignId,
					Long.parseLong(fields[0]),
					requestStatus(fields[2]),
					sequence < 0 ? null : sequence,
					remainingStock < 0 ? null : remainingStock,
					Instant.ofEpochMilli(decidedAt));
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException("쿠폰 요청 상태 형식이 올바르지 않습니다.", exception);
		}
	}

	private IssueRequestStatus requestStatus(String value) {
		try {
			return IssueRequestStatus.fromRedisCode(Long.parseLong(value));
		} catch (NumberFormatException exception) {
			// 구형 문자열 상태 호환
			return IssueRequestStatus.valueOf(value);
		}
	}

	public CouponIssueCompensationResult compensate(Long campaignId, Long userId, UUID requestId) {
		if (requestId == null) {
			throw new IllegalArgumentException("requestId가 필요합니다.");
		}
		CouponRedisKeys.CampaignKeys keys = CouponRedisKeys.campaign(campaignId);
		CouponRedisKeys.BitmapLocation bitmap = keys.bitmap(userId);
		Long code = redisTemplate.execute(
				compensateScript,
				List.of(
						keys.metadata(),
						keys.stock(),
						bitmap.key(),
						keys.requests(),
						keys.issueStream()),
				String.valueOf(userId),
				String.valueOf(bitmap.offset()),
				requestId.toString());

		if (code == null) {
			throw new IllegalStateException("쿠폰 발급 보상 결과가 없습니다.");
		}
		return CouponIssueCompensationResult.from(code);
	}

	private long number(Object value) {
		try {
			if (value instanceof Number number) {
				return number.longValue();
			}
			return Long.parseLong(String.valueOf(value));
		} catch (NumberFormatException exception) {
			throw new IllegalStateException("쿠폰 발급 Lua 숫자 결과가 올바르지 않습니다.", exception);
		}
	}
}
