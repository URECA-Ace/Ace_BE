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
 * 1. 동시성 정합성 (발급 순번 중복 검증)
 *
 * 동일 이벤트 내에서 발급 순번(issue_sequence)이 중복 할당되는 동시성 문제(Race Condition)를 잡아냅니다.
 * Redis의 INCR 연산이 누락되거나 동시 다발적 트랜잭션 충돌로 인해 순서가 꼬인 경우를 식별합니다.
 */
@Component
@RequiredArgsConstructor
public class DuplicateSequenceCheck implements ConsistencyCheck {

	private static final int SAMPLE_LIMIT = 20;
	private final NamedParameterJdbcTemplate jdbcTemplate;

	private static final String SQL = """
            SELECT event_id, issue_sequence, COUNT(*) as sequence_count
            FROM coupon_issue
            WHERE (:eventId IS NULL OR event_id = :eventId)
              AND issue_sequence IS NOT NULL
            GROUP BY event_id, issue_sequence
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
						"eventId", row.get("event_id"),
						"issueSequence", row.get("issue_sequence"),
						"sequenceCount", row.get("sequence_count")
				)).toList());
		diff.put("reason", "동시성 방어 뚫림: 동일한 이벤트에서 발급 순번(issue_sequence)이 중복 할당되었습니다.");

		return CheckOutcome.fail(violations.size(), diff);
	}
}
