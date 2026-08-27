package com.ace.consistency.check;

import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.Scope;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 1인 1매 제한(중복 발급) 검사.
 *
 * 동일 user_id + event_id 조합의 활성 발급 건수(ISSUED+USED+EXPIRED)가
 * coupon_event.per_user_limit을 넘는 경우를 위반으로 본다.
 *
 * GROUP BY를 coupon_issue 단일 테이블에만 걸어서(서브쿼리) idx_issue_event_user_status
 * 복합 인덱스를 그대로 타게 하고, coupon_event 조인은 이미 좁혀진 결과에만 붙인다.
 *
 * EVENT 스코프: 특정 event_id 하나만 검사
 * ALL 스코프: 전체 이벤트 대상으로 검사
 */
@Component
@RequiredArgsConstructor
public class DuplicateConsistencyCheck implements ConsistencyCheck {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	private static final String EVENT_SQL = """
            SELECT sub.event_id, sub.user_id, sub.active_issued_count, ce.per_user_limit
            FROM (
                SELECT event_id, user_id, COUNT(*) AS active_issued_count
                FROM coupon_issue
                WHERE status IN ('ISSUED','USED','EXPIRED')
                  AND event_id = :eventId
                GROUP BY event_id, user_id
            ) sub
            JOIN coupon_event ce ON ce.event_id = sub.event_id
            WHERE sub.active_issued_count > ce.per_user_limit
            """;

	private static final String ALL_SQL = """
            SELECT sub.event_id, sub.user_id, sub.active_issued_count, ce.per_user_limit
            FROM (
                SELECT event_id, user_id, COUNT(*) AS active_issued_count
                FROM coupon_issue
                WHERE status IN ('ISSUED','USED','EXPIRED')
                  AND event_id IN (:eventIds)
                  AND created_at < :to
                GROUP BY event_id, user_id
            ) sub
            JOIN coupon_event ce ON ce.event_id = sub.event_id
            WHERE sub.active_issued_count > ce.per_user_limit
            """;

	private static final String AS_OF_RANGE_SQL = """
            SELECT sub.event_id, sub.user_id, sub.active_issued_count, ce.per_user_limit
            FROM (
                SELECT event_id, user_id, COUNT(*) AS active_issued_count
                FROM coupon_issue
                WHERE status IN ('ISSUED','USED','EXPIRED')
                  AND created_at < :to
                  AND created_at >= :from
                GROUP BY event_id, user_id
            ) sub
            JOIN coupon_event ce ON ce.event_id = sub.event_id
            WHERE sub.active_issued_count > ce.per_user_limit
            """;

	@Override
	public Set<Scope.ScopeType> supportedScopeTypes() {
		return Set.of(Scope.ScopeType.EVENT, Scope.ScopeType.ALL, Scope.ScopeType.AS_OF_RANGE);
	}

	@Override
	public CheckOutcome check(Scope scope) {
		List<Map<String, Object>> violations;
		switch (scope.getType()) {
			case EVENT -> {
				MapSqlParameterSource params = new MapSqlParameterSource("eventId", scope.getEventId());
				violations = jdbcTemplate.queryForList(EVENT_SQL, params);
			}
			case ALL -> {
				MapSqlParameterSource params = new MapSqlParameterSource()
						.addValue("eventIds", scope.getEventIds())
						.addValue("to", scope.getTo());
				violations = jdbcTemplate.queryForList(ALL_SQL, params);
			}
			case AS_OF_RANGE -> {
				MapSqlParameterSource params = new MapSqlParameterSource()
						.addValue("from", scope.getFrom())
						.addValue("to", scope.getTo());
				violations = jdbcTemplate.queryForList(AS_OF_RANGE_SQL, params);
			}
			default -> throw new IllegalArgumentException("Unsupported scope type: " + scope.getType());
		}

		if (violations.isEmpty()) {
			return CheckOutcome.pass();
		}

		return CheckOutcome.fail(violations.size(), buildDiffDetail(violations));
	}

	private Map<String, Object> buildDiffDetail(List<Map<String, Object>> violations) {
		Map<String, Object> diff = new LinkedHashMap<>();
		diff.put("violationCount", violations.size());
		diff.put("sample", violations.stream()
				.map(row -> Map.of(
						"eventId", row.get("event_id"),
						"userId", row.get("user_id"),
						"activeIssuedCount", row.get("active_issued_count"),
						"perUserLimit", row.get("per_user_limit")
				))
				.toList());
		return diff;
	}
}