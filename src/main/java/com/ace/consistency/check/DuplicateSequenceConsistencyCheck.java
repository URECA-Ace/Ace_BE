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
public class DuplicateSequenceConsistencyCheck implements ConsistencyCheck {

	private final NamedParameterJdbcTemplate jdbcTemplate;
	private static final String SCOPE_CONDITION = """
			(
				(:scopeMode = 'EVENT' AND event_id = :eventId)
				OR (:scopeMode = 'ALL' AND event_id IN (:eventIds) AND created_at < :to)
			)
			""";

	// total_violation_count 는 조회된 전체 에러 건수
	private static final String SQL = """
			SELECT sub.event_id, sub.issue_sequence, sub.sequence_count,
			       SUM(sub.sequence_count) OVER() AS total_violation_count
			FROM (
			    SELECT event_id, issue_sequence, COUNT(*) as sequence_count
			    FROM coupon_issue
			    WHERE %s
			      AND issue_sequence IS NOT NULL
			    GROUP BY event_id, issue_sequence
			    HAVING COUNT(*) > 1
			) sub
			""".formatted(SCOPE_CONDITION);
	// Scope.ofEvent(eventId)     /     테스트용 ALL(global), Scope.all(to)
	@Override
	public Set<Scope.ScopeType> supportedScopeTypes() {
		return Set.of(Scope.ScopeType.EVENT, Scope.ScopeType.ALL);
	}

	@Override
	public CheckOutcome check(Scope scope) {
		MapSqlParameterSource params = scopeParameters(scope);
		List<Map<String, Object>> violations = jdbcTemplate.queryForList(SQL, params);

		if (violations.isEmpty()) {
			return CheckOutcome.pass();
		}

		int violationCount = ((Number) violations.getFirst().get("total_violation_count")).intValue();
		List<Map<String, Object>> sample = new java.util.ArrayList<>(violations.size());
		for (Map<String, Object> violation : violations) {
			Map<String, Object> sampleRow = new LinkedHashMap<>(violation);
			sampleRow.remove("total_violation_count");
			sample.add(sampleRow);
		}

		Map<String, Object> diff = new LinkedHashMap<>();
		diff.put("sample", sample);
		diff.put("reason", "동시성 방어 뚫림: 동일한 이벤트에서 발급 순번(issue_sequence)이 중복 할당되었습니다.");

		return CheckOutcome.fail(violationCount, diff);
	}

	private MapSqlParameterSource scopeParameters(Scope scope) {
		boolean eventScope = scope.getType() == Scope.ScopeType.EVENT;
		return new MapSqlParameterSource()
				.addValue("scopeMode", eventScope ? "EVENT" : "ALL")
				.addValue("eventId", eventScope ? scope.getEventId() : null)
				// eventIds가 빈 리스트일 경우 IN 절 SQL 문법 에러 방지를 위해 의미 없는 값(-1) 세팅
				.addValue("eventIds", eventScope ? null : (scope.getEventIds().isEmpty() ? List.of(-1L) : scope.getEventIds()))
				.addValue("to", eventScope ? null : scope.getTo());
	}
}
