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
 * coupon_issue의 현재 상태와 가장 최근 coupon_history의 도착 상태가 같은지 검사한다.
 * 발급 건에는 최초 ISSUED 이력부터 상태 변경 이력이 항상 남는다는 현재 MVP 정책을 전제로 하므로,
 * 이력이 전혀 없는 발급 건도 상태 감사 기록이 유실된 위반으로 처리한다.
 */
@Component
@RequiredArgsConstructor
public class CouponIssueHistoryStateConsistencyCheck implements ConsistencyCheck {

	private static final int SAMPLE_LIMIT = 20;
	private final NamedParameterJdbcTemplate jdbcTemplate;

	private static final String SCOPED_ISSUE_CONDITION = """
			(
				(:scopeMode = 'EVENT' AND scoped_issue.event_id = :eventId)
				OR :scopeMode = 'ALL_GLOBAL'
				OR (:scopeMode = 'ALL_PAGE' AND scoped_issue.event_id IN (:eventIds)
				    AND scoped_issue.created_at < :to)
			)
			""";
	private static final String ISSUE_CONDITION = """
			(
				(:scopeMode = 'EVENT' AND ci.event_id = :eventId)
				OR :scopeMode = 'ALL_GLOBAL'
				OR (:scopeMode = 'ALL_PAGE' AND ci.event_id IN (:eventIds) AND ci.created_at < :to)
			)
			""";
	private static final String LATEST_HISTORY_CTE = """
			WITH latest_history AS (
				SELECT h.history_id, h.issue_id, h.from_status, h.to_status, h.occurred_at,
				       ROW_NUMBER() OVER (
				           PARTITION BY h.issue_id
				           ORDER BY h.occurred_at DESC, h.history_id DESC
				       ) AS rn
				FROM coupon_history h
				JOIN coupon_issue scoped_issue ON scoped_issue.issue_id = h.issue_id
				WHERE %s
			)
			""".formatted(SCOPED_ISSUE_CONDITION);
	private static final String LATEST_HISTORY_JOIN = """
			LEFT JOIN latest_history latest
			  ON latest.issue_id = ci.issue_id AND latest.rn = 1
			""";
	private static final String CONDITION = """
			%s
			AND (latest.history_id IS NULL OR NOT (latest.to_status <=> ci.status))
			""".formatted(ISSUE_CONDITION);
	private static final String COUNT_SQL = LATEST_HISTORY_CTE + "SELECT COUNT(*) FROM coupon_issue ci "
			+ LATEST_HISTORY_JOIN + " WHERE " + CONDITION;
	private static final String SAMPLE_SQL = """
			%s
			SELECT ci.issue_id, ci.event_id, ci.status AS current_status,
			       latest.history_id, latest.from_status, latest.to_status, latest.occurred_at,
			       CASE
			         WHEN latest.history_id IS NULL THEN 'NO_HISTORY'
			         ELSE 'LATEST_STATUS_MISMATCH'
			       END AS violation_type
			FROM coupon_issue ci
			%s
			WHERE %s
			ORDER BY ci.issue_id
			LIMIT %d
			""".formatted(LATEST_HISTORY_CTE, LATEST_HISTORY_JOIN, CONDITION, SAMPLE_LIMIT);

	@Override
	public Set<Scope.ScopeType> supportedScopeTypes() {
		return Set.of(Scope.ScopeType.EVENT, Scope.ScopeType.ALL);
	}

	@Override
	public CheckOutcome check(Scope scope) {
		boolean eventScope = scope.getType() == Scope.ScopeType.EVENT;
		boolean pagedAll = scope.getType() == Scope.ScopeType.ALL && scope.getEventIds() != null;
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("scopeMode", eventScope ? "EVENT" : pagedAll ? "ALL_PAGE" : "ALL_GLOBAL")
				.addValue("eventId", eventScope ? scope.getEventId() : null)
				.addValue("eventIds", pagedAll ? scope.getEventIds() : List.of(-1L))
				.addValue("to", scope.getType() == Scope.ScopeType.ALL ? scope.getTo() : null);
		Integer count = jdbcTemplate.queryForObject(COUNT_SQL, params, Integer.class);
		int violationCount = count == null ? 0 : count;
		if (violationCount == 0) {
			return CheckOutcome.pass();
		}

		List<Map<String, Object>> sample = jdbcTemplate.queryForList(SAMPLE_SQL, params);
		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("rule", "coupon_issue.status must equal the latest coupon_history.to_status");
		detail.put("sample", sample);
		return CheckOutcome.fail(violationCount, detail);
	}
}
