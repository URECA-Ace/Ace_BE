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
// 빠르게 여러번 눌러서 같은 상태변화가 발생한 경우를 잡아냄.
/**
 * 2. 동시성 정합성 (상태 갱신 충돌 방지)
 *
 * 동일한 쿠폰(issue_id)이 여러 번 사용(USED)되거나 여러 번 취소(CANCELED)되는 동시성 뚫림 현상을 식별합니다.
 * 쿠폰 사용 로직의 Lock이 정상적으로 동작하지 않아 발생한 Lost Update 문제를 잡아냅니다.
 * 
*/
@Component
@RequiredArgsConstructor
public class ConcurrentStatusRaceCheck implements ConsistencyCheck {

	private static final int SAMPLE_LIMIT = 20;
	private final NamedParameterJdbcTemplate jdbcTemplate;

	private static final String SQL = """
            SELECT ch.issue_id, ch.to_status, COUNT(*) as status_count
            FROM coupon_history ch
            JOIN coupon_issue ci ON ci.issue_id = ch.issue_id
            WHERE ch.to_status IN ('USED', 'CANCELED', 'EXPIRED')
              AND (:eventId IS NULL OR ci.event_id = :eventId)
            GROUP BY ch.issue_id, ch.to_status
            HAVING COUNT(*) > 1
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
						"status", row.get("to_status"),
						"count", row.get("status_count")
				)).toList());
		diff.put("reason", "상태 전이 충돌: 동일한 쿠폰이 여러 번 사용(USED)되거나 취소(CANCELED)되는 Lost Update 발생");

		return CheckOutcome.fail(violations.size(), diff);
	}
}
