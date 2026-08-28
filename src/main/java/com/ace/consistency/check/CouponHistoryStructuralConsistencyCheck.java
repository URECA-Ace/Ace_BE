package com.ace.consistency.check;

import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.ViolationTargetType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** coupon_history 자체의 필수값, 시각 순서와 허용된 상태 전이 형태를 검사한다. */
@Component
@RequiredArgsConstructor
public class CouponHistoryStructuralConsistencyCheck implements ConsistencyCheck {
	@Override
	public String getLabel() {
		return "쿠폰 이력 구조 검사";
	}

	private final NamedParameterJdbcTemplate jdbcTemplate;

	private static final String SCOPE_CONDITION = """
			(
				(:scopeMode = 'EVENT' AND ci.event_id = :eventId)
				OR (:scopeMode = 'AS_OF_RANGE' AND (
					h.recorded_at IS NULL
					OR (h.recorded_at >= :from AND h.recorded_at < :to)
				))
				OR :scopeMode = 'ALL_GLOBAL'
				OR (:scopeMode = 'ALL_PAGE' AND ci.event_id IN (:eventIds) AND ci.created_at < :to)
			)
			""";
	private static final String BASE_CONDITION = """
			(
				h.to_status IS NULL OR h.occurred_at IS NULL OR h.recorded_at IS NULL
				OR h.recorded_at < h.occurred_at
				OR h.to_status NOT IN ('ISSUED','USED','EXPIRED','CANCELED')
				OR (h.from_status IS NULL AND h.to_status <> 'ISSUED')
				OR (h.from_status IS NOT NULL AND NOT (
					(h.from_status = 'ISSUED' AND h.to_status IN ('USED','EXPIRED','CANCELED'))
					OR (h.from_status = 'USED' AND h.to_status = 'ISSUED')
				))
			)
			""";

	private static final String FROM_SQL =
			" FROM coupon_history h LEFT JOIN coupon_issue ci ON ci.issue_id = h.issue_id WHERE ";
	private static final String VIOLATION_SQL = ("""
			SELECT h.history_id, h.issue_id, h.from_status, h.to_status, h.occurred_at, h.recorded_at,
			       COUNT(*) OVER() AS total_violation_count,
			       CASE
			         WHEN h.to_status IS NULL THEN 'MISSING_TO_STATUS'
			         WHEN h.occurred_at IS NULL OR h.recorded_at IS NULL THEN 'MISSING_TIMESTAMP'
			         WHEN h.recorded_at < h.occurred_at THEN 'INVALID_TIMESTAMP_ORDER'
			         WHEN h.to_status NOT IN ('ISSUED','USED','EXPIRED','CANCELED') THEN 'INVALID_TO_STATUS'
			         WHEN h.from_status IS NULL AND h.to_status <> 'ISSUED' THEN 'INVALID_INITIAL_TRANSITION'
			         ELSE 'INVALID_STATUS_TRANSITION'
			       END AS violation_type
			%s%s AND %s ORDER BY h.history_id
			""").formatted(FROM_SQL, SCOPE_CONDITION, BASE_CONDITION);

	@Override
	public Set<Scope.ScopeType> supportedScopeTypes() {
		return Set.of(Scope.ScopeType.EVENT, Scope.ScopeType.AS_OF_RANGE, Scope.ScopeType.ALL);
	}

	@Override
	public CheckOutcome check(Scope scope) {
		MapSqlParameterSource params = scopeParameters(scope);
		List<Map<String, Object>> violations = jdbcTemplate.queryForList(VIOLATION_SQL, params);
		if (violations.isEmpty()) {
			return CheckOutcome.pass();
		}
		int violationCount = ((Number) violations.getFirst().get("total_violation_count")).intValue();
		List<Violation> violationList = new ArrayList<>(violations.size());
		for (Map<String, Object> violation : violations) {
			Map<String, Object> violationDetail = new LinkedHashMap<>(violation);
			violationDetail.remove("total_violation_count");
			violationList.add(new Violation(ViolationTargetType.HISTORY, ((Number) violation.get("history_id")).longValue(), violationDetail));
		}

		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("rule", "coupon_history structural fields and allowed transition shape");
		return CheckOutcome.fail(violationCount, detail, violationList);
	}

	private MapSqlParameterSource scopeParameters(Scope scope) {
		boolean eventScope = scope.getType() == Scope.ScopeType.EVENT;
		boolean rangeScope = scope.getType() == Scope.ScopeType.AS_OF_RANGE;
		boolean pagedAll = scope.getType() == Scope.ScopeType.ALL && scope.getEventIds() != null;
		return new MapSqlParameterSource()
				.addValue("scopeMode", eventScope ? "EVENT"
						: rangeScope ? "AS_OF_RANGE"
						: pagedAll ? "ALL_PAGE" : "ALL_GLOBAL")
				.addValue("eventId", eventScope ? scope.getEventId() : null)
				.addValue("eventIds", pagedAll ? scope.getEventIds() : List.of(-1L))
				.addValue("from", rangeScope ? scope.getFrom() : null)
				.addValue("to", rangeScope || scope.getType() == Scope.ScopeType.ALL ? scope.getTo() : null);
	}
}
