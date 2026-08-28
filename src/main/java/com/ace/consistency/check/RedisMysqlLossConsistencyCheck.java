package com.ace.consistency.check;

import com.ace.common.ErrorCode;
import com.ace.common.exception.ConsistencyCheckException;
import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.ViolationTargetType;
import com.ace.coupon.redis.CouponRedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 이벤트·로그 기반 검증 (Redis vs MySQL 유실 검증)
 *
 * MySQL의 최초 총 재고(Total Stock)와 Redis의 실시간 잔여 재고(Remaining Stock)를 비교해 산출한 기대 발급 건수와
 * MySQL의 실제 적재 건수를 대조하여,  비동기 파이프라인 구간에서
 * 메시지가 유실(Loss)되었는지 Cross-Datastore 방식으로 검증합니다.
 * (Redis 장애 복구 시 잔여 재고로 재초기화되는 상황에서도 안전하게 동작하도록 설계됨)
 */
@Component
@RequiredArgsConstructor
public class RedisMysqlLossConsistencyCheck implements ConsistencyCheck {
	@Override
	public String getLabel() {
		return "Redis·MySQL 유실 정합성 검사";
	}

	private final NamedParameterJdbcTemplate jdbcTemplate;
	private final StringRedisTemplate redisTemplate;

	private static final String COUNT_SQL = """
            SELECT total_stock, 
                   (SELECT COUNT(*) FROM coupon_issue WHERE event_id = :eventId AND issue_sequence IS NOT NULL) as actual_count
            FROM coupon_event
            WHERE event_id = :eventId
            """;

	@Override
	public Set<Scope.ScopeType> supportedScopeTypes() {
		// Redis 키가 이벤트(Campaign) 단위로 묶여 있으므로 EVENT 스코프만 지원합니다.
		return Set.of(Scope.ScopeType.EVENT);
	}

	@Override
	public CheckOutcome check(Scope scope){
		Long eventId = scope.getEventId();

		// 1. 비동기 파이프라인(Stream)에 아직 처리 중인 메시지(Pending)가 있는지 확인
		String streamKey = CouponRedisKeys.campaign(eventId).issueStream();
		long pendingCount = 0;
		try {
			pendingCount = redisTemplate.opsForStream().groups(streamKey).stream()
					.mapToLong(org.springframework.data.redis.connection.stream.StreamInfo.XInfoGroup::pendingCount)
					.sum();
		} catch (RedisSystemException e) {
			// 스트림 키나 컨슈머 그룹이 아직 생성되지 않은 경우 (발급 내역이 아예 없는 초기 상태 등)
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
		
		if (pendingCount > 0) {
			throw new ConsistencyCheckException(ErrorCode.CHECK_POSTPONED,
					"PENDING 큐에 남은 메시지가 있어 검증을 수행할 수 없습니다. (pendingCount: " + pendingCount + ")");
		}

		// 2. MySQL에서 최초 총 재고(Total Stock)와 실제 적재 건수(Actual Count) 동시 조회
		MapSqlParameterSource params = new MapSqlParameterSource("eventId", eventId);
		List<Map<String, Object>> dbResults = jdbcTemplate.queryForList(COUNT_SQL, params);
		if (dbResults.isEmpty()) {
			return CheckOutcome.pass(); // 이벤트가 존재하지 않으면 검증 불필요
		}
		
		long totalStock = ((Number) dbResults.get(0).get("total_stock")).longValue();
		long actualCount = ((Number) dbResults.get(0).get("actual_count")).longValue();

		// 3. Redis에서 잔여 재고(Remaining Stock) 조회
		String stockKey = CouponRedisKeys.campaign(eventId).stock();
		String stockValue = redisTemplate.opsForValue().get(stockKey);
		
		// Redis에 값이 없다면 두 가지 경우가 있습니다:
		// 1. 이벤트 시작 전: DB 적재 건수(actualCount)도 0이어야 함 -> totalStock 반환
		// 2. 이벤트 마감 후 만료됨: DB 적재 건수가 0보다 큼 -> 비교 불가(예외 던짐)
		long remainingStock;
		if (stockValue == null) {
			if (actualCount > 0) {
				throw new ConsistencyCheckException(ErrorCode.CHECK_IMPOSSIBLE,
						"Redis 잔여 재고 데이터가 만료되어 정합성 검증을 수행할 수 없습니다.");
			}
			remainingStock = totalStock;
		} else {
			remainingStock = Long.parseLong(stockValue);
		}
		
		// 장애 복구로 인해 Redis 잔여 재고가 재초기화 되었더라도, 절대 변하지 않는 기준점(총 재고)을 바탕으로 기대 건수 계산
		long expectedCount = totalStock - remainingStock;

		// 4. 건수 비교
		if (expectedCount == actualCount) {
			return CheckOutcome.pass();
		}

		// 유실 또는 불일치 발생
		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("eventId", eventId);
		detail.put("totalStock (MySQL)", totalStock);
		detail.put("remainingStock (Redis)", remainingStock);
		detail.put("expectedCount (Calculated)", expectedCount);
		detail.put("actualCount (MySQL)", actualCount);
		detail.put("lostCount", expectedCount - actualCount);
		detail.put("reason", "비동기 파이프라인 이벤트 유실 의심: Redis에서 승인된 쿠폰 수량과 DB에 적재된 수량이 일치하지 않습니다.");

		// 위반 건수는 1건(이벤트 단위 불일치 1건)으로 처리
		return CheckOutcome.fail(1, detail, List.of(new Violation(ViolationTargetType.EVENT, eventId, detail)));
	}
}
