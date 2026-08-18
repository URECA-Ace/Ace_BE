package com.ace.consistency.check;

import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.Scope;
import com.ace.coupon.redis.CouponRedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 6. 이벤트·로그 기반 검증 (Redis vs MySQL 유실 검증)
 *
 * Redis의 발급 순번(Sequence) 카운터와 MySQL의 실제 적재 건수를 대조하여,
 * Kafka 비동기 파이프라인 구간(Redis -> Relay -> Kafka -> Consumer)에서
 * 메시지가 유실(Loss)되었는지 Cross-Datastore 방식으로 검증합니다.
 */
@Component
@RequiredArgsConstructor
public class RedisMysqlLossConsistencyCheck implements ConsistencyCheck {

	private final NamedParameterJdbcTemplate jdbcTemplate;
	private final StringRedisTemplate redisTemplate;

	private static final String COUNT_SQL = """
            SELECT COUNT(*) 
            FROM coupon_issue 
            WHERE event_id = :eventId 
              AND issue_sequence IS NOT NULL
            """;

	@Override
	public Set<Scope.ScopeType> supportedScopeTypes() {
		// Redis 키가 이벤트(Campaign) 단위로 묶여 있으므로 EVENT 스코프만 지원합니다.
		return Set.of(Scope.ScopeType.EVENT);
	}

	@Override
	public CheckOutcome check(Scope scope) {
		if (scope.getType() != Scope.ScopeType.EVENT || scope.getEventId() == null) {
			throw new IllegalArgumentException("RedisMysqlLossConsistencyCheck는 EVENT 스코프(eventId 필수)만 지원합니다.");
		}

		Long eventId = scope.getEventId();
		
		// 1. Redis에서 기대 발급 건수(Sequence MAX) 조회
		String sequenceKey = CouponRedisKeys.campaign(eventId).sequence();
		String redisValue = redisTemplate.opsForValue().get(sequenceKey);
		
		// Redis에 값이 없다면 쿠폰이 한 건도 발급 승인되지 않은 상태
		long expectedCount = (redisValue == null) ? 0L : Long.parseLong(redisValue);

		// 2. MySQL에서 실제 저장 건수 조회
		MapSqlParameterSource params = new MapSqlParameterSource("eventId", eventId);
		Integer mysqlCount = jdbcTemplate.queryForObject(COUNT_SQL, params, Integer.class);
		long actualCount = (mysqlCount == null) ? 0L : mysqlCount;

		// 3. 건수 비교
		if (expectedCount == actualCount) {
			return CheckOutcome.pass();
		}

		// 유실 또는 불일치 발생
		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("eventId", eventId);
		detail.put("expectedCount (Redis)", expectedCount);
		detail.put("actualCount (MySQL)", actualCount);
		detail.put("lostCount", expectedCount - actualCount);
		detail.put("reason", "비동기 파이프라인 이벤트 유실 의심: Redis에서 승인된 쿠폰 수량과 DB에 적재된 수량이 일치하지 않습니다.");

		// 위반 건수는 1건(이벤트 단위 불일치 1건)으로 처리
		return CheckOutcome.fail(1, detail);
	}
}
