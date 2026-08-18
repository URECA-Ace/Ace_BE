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
 * 3. 멱등성 정합성 (짧은 시간 내 중복 트랜잭션 방지)
 *
 * 동일한 쿠폰(issue_id)에 대해 똑같은 상태 전이(from_status -> to_status)가 
 * 2초 이내에 여러 번 발생했는지 시계열로 촘촘하게 검사합니다.
 * 네트워크 지연으로 인한 클라이언트 재시도(Retry)가 서버에서 멱등하게 처리되지 않은 케이스를 찾습니다.
 */
@Component
@RequiredArgsConstructor
public class IdempotentHistoryCheck implements ConsistencyCheck {

	private static final int SAMPLE_LIMIT = 20;
	private final NamedParameterJdbcTemplate jdbcTemplate;

	private static final String SQL = """
            SELECT a.issue_id, a.from_status, a.to_status, a.occurred_at as time1, b.occurred_at as time2
            FROM coupon_history a
            JOIN coupon_history b 
              ON a.issue_id = b.issue_id 
              AND a.id != b.id
              AND a.from_status <=> b.from_status
              AND a.to_status = b.to_status
            JOIN coupon_issue ci ON ci.issue_id = a.issue_id
            WHERE a.occurred_at <= b.occurred_at
              AND TIMESTAMPDIFF(SECOND, a.occurred_at, b.occurred_at) < 2
              AND (:eventId IS NULL OR ci.event_id = :eventId)
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
		diff.put("violationCount", violations.size());
		diff.put("sample", violations.stream()
				.limit(SAMPLE_LIMIT)
				.map(row -> Map.of(
						"issueId", row.get("issue_id"),
						"fromStatus", row.get("from_status"),
						"toStatus", row.get("to_status"),
						"time1", row.get("time1"),
						"time2", row.get("time2")
				)).toList());
		diff.put("reason", "멱등성 위반: 매우 짧은 시간(2초 이내)에 동일한 상태 전이 요청이 중복 처리되었습니다.");

		return CheckOutcome.fail(violations.size(), diff);
	}
}
