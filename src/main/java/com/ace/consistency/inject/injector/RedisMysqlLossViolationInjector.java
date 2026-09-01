package com.ace.consistency.inject.injector;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.ace.common.ErrorCode;
import com.ace.common.exception.ConsistencyCheckException;
import com.ace.consistency.inject.ConsistencyViolationInjector;
import com.ace.consistency.inject.InjectionResult;
import com.ace.coupon.redis.CouponRedisKeys;

import lombok.RequiredArgsConstructor;

/**
 * RedisMysqlLossConsistencyCheck용 위반 주입기.
 * Redis의 잔여 재고(coupon:{campaign:ID}:stock)를 1 감소시켜, "Redis 기준으로는 승인됐지만
 * MySQL에는 아직 적재되지 않은" 비동기 파이프라인 메시지 유실 상황을 재현한다.
 * MySQL 쪽은 건드리지 않는다.
 */
@Component
@RequiredArgsConstructor
public class RedisMysqlLossViolationInjector implements ConsistencyViolationInjector {

	private final StringRedisTemplate redisTemplate;

	private static final String CHECK_NAME = "RedisMysqlLossConsistencyCheck";

	@Override
	public String checkName() {
		return CHECK_NAME;
	}

	@Override
	public String description() {
		return "Redis 잔여 재고를 1 감소시켜 MySQL과의 파이프라인 유실을 재현합니다.";
	}

	@Override
	public InjectionResult inject(Long eventId) {
		String stockKey = CouponRedisKeys.campaign(eventId).stock();
		if (!Boolean.TRUE.equals(redisTemplate.hasKey(stockKey))) {
			throw new ConsistencyCheckException(ErrorCode.INJECTION_TARGET_NOT_FOUND,
					"Redis 잔여 재고 키가 없습니다. eventId=" + eventId);
		}
		Long remaining = redisTemplate.opsForValue().decrement(stockKey);
		return new InjectionResult(CHECK_NAME, eventId,
				String.format("이벤트 %d의 Redis 잔여 재고를 1 감소시켜(현재 %d) 파이프라인 유실을 만들었습니다.", eventId, remaining));
	}
}
