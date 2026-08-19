package com.ace.consistency.check;

import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.Scope;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 2. 상태 머신 정합성 (이력 연속성 검증)
 *
 * 쿠폰 이력(coupon_history) 테이블에서 상태 전이의 체인이 끊어지거나 과거 상태로 덮어씌워진 경우를 식별합니다.
 * "현재 레코드의 출발지(from_status)는 반드시 직전 레코드의 목적지(prev_to_status)와 일치해야 한다"는
 * 불변의 법칙을 검증하여, 동시성 충돌이나 멱등성 실패, 잘못된 로직으로 인한 덮어쓰기를 모두 잡아냅니다.
 */
@Component
@RequiredArgsConstructor
public class StateMachineConsistencyCheck implements ConsistencyCheck {

	private static final int SAMPLE_LIMIT = 20;
	private final NamedParameterJdbcTemplate jdbcTemplate;

	private static final String SQL = """
            SELECT sub.issue_id, sub.prev_to_status, sub.from_status, sub.to_status
            FROM (
                SELECT ch.issue_id, 
                       ch.from_status, 
                       ch.to_status,
                       LAG(ch.to_status) OVER (PARTITION BY ch.issue_id ORDER BY ch.occurred_at, ch.history_id) as prev_to_status,
                       ci.event_id
                FROM coupon_history ch
                JOIN coupon_issue ci ON ci.issue_id = ch.issue_id
            ) sub
            WHERE sub.prev_to_status IS NOT NULL 
              AND NOT (sub.from_status <=> sub.prev_to_status)
              AND (:eventId IS NULL OR sub.event_id = :eventId)
            """;

	@Override
	public Set<Scope.ScopeType> supportedScopeTypes() {
		return Set.of(Scope.ScopeType.EVENT, Scope.ScopeType.ALL);
	}

	@Override
	public CheckOutcome check(Scope scope) {
		Long eventIdFilter = scope.getType() == Scope.ScopeType.EVENT ? scope.getEventId() : null;
		MapSqlParameterSource params = new MapSqlParameterSource("eventId", eventIdFilter);

		List<Map<String, Object>> violations = jdbcTemplate.queryForList(SQL, params);

		if (violations.isEmpty()) {
			return CheckOutcome.pass();
		}

		Map<String, Object> diff = new LinkedHashMap<>();
		diff.put("sample", violations.stream()
				.limit(SAMPLE_LIMIT)
				.map(row -> Map.of(
						"issueId", row.get("issue_id"),
						"prevToStatus", row.get("prev_to_status"),
						"fromStatus", row.get("from_status"),
						"toStatus", row.get("to_status")
				)).toList());
		diff.put("reason", "상태 머신 연속성 붕괴: 이전 상태의 목적지(prev_to_status)와 현재 상태의 출발지(from_status)가 일치하지 않습니다. 동시성 충돌이나 낡은 데이터 덮어쓰기가 의심됩니다.");

		return CheckOutcome.fail(violations.size(), diff);
	}
}
