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

/** coupon_issue 한 행만으로 판단할 수 있는 필수값, 범위, 시각, 상태별 필드 조합을 검사한다. */
@Component
@RequiredArgsConstructor
public class CouponIssueStructuralConsistencyCheck implements ConsistencyCheck {

	private static final int SAMPLE_LIMIT = 20;
	private final NamedParameterJdbcTemplate jdbcTemplate;

	private static final String SCOPE_CONDITION = """
			(
				(:scopeMode = 'EVENT' AND ci.event_id = :eventId)
				OR :scopeMode = 'ALL_GLOBAL'
				OR (:scopeMode = 'ALL_PAGE' AND ci.event_id IN (:eventIds) AND ci.created_at < :to)
			)
			""";
	private static final String BASE_CONDITION = """
			(
				ci.user_id IS NULL OR ci.issue_sequence IS NULL
				OR ci.issue_sequence <= 0 OR ci.request_id IS NULL OR ci.request_id NOT REGEXP
					'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
				OR (ci.message_id IS NOT NULL AND ci.message_id NOT REGEXP
					'^[1-9][0-9]*-[0-9]+-[0-9]+$')
				OR ci.status IS NULL OR ci.status NOT IN ('ISSUED','USED','EXPIRED')
				OR ci.issued_at IS NULL OR ci.valid_from IS NULL OR ci.valid_to IS NULL OR ci.created_at IS NULL
				OR ci.issued_at > ci.valid_from OR ci.valid_from >= ci.valid_to OR ci.created_at < ci.issued_at
				OR (ci.status IN ('ISSUED','EXPIRED') AND ci.used_at IS NOT NULL)
				OR (ci.status = 'USED' AND (ci.used_at IS NULL OR ci.used_at < ci.valid_from))
			)
			""";

	private static final String VIOLATION_SQL = """
			SELECT ci.issue_id, ci.event_id, ci.user_id, ci.status,
			       ci.issued_at, ci.valid_from, ci.valid_to, ci.used_at,
			       COUNT(*) OVER() AS total_violation_count,
			       CASE
			         WHEN ci.user_id IS NULL THEN 'MISSING_USER_ID'
			         WHEN ci.issue_sequence IS NULL OR ci.issue_sequence <= 0 THEN 'INVALID_ISSUE_SEQUENCE'
				         WHEN ci.request_id IS NULL OR ci.request_id NOT REGEXP
				              '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
				              THEN 'INVALID_REQUEST_ID'
				         WHEN ci.message_id IS NOT NULL AND ci.message_id NOT REGEXP
				              '^[1-9][0-9]*-[0-9]+-[0-9]+$'
				              THEN 'INVALID_MESSAGE_ID_FORMAT'
			         WHEN ci.status IS NULL OR ci.status NOT IN ('ISSUED','USED','EXPIRED')
			              THEN 'INVALID_STATUS'
			         WHEN ci.issued_at IS NULL OR ci.valid_from IS NULL
			              OR ci.valid_to IS NULL OR ci.created_at IS NULL THEN 'MISSING_TIMESTAMP'
			         WHEN ci.issued_at > ci.valid_from OR ci.valid_from >= ci.valid_to
			              OR ci.created_at < ci.issued_at THEN 'INVALID_TIMESTAMP_ORDER'
			         ELSE 'INVALID_STATUS_FIELDS'
			       END AS violation_type
			FROM coupon_issue ci
			WHERE %s AND %s
			ORDER BY ci.issue_id
			LIMIT %d
			""".formatted(SCOPE_CONDITION, BASE_CONDITION, SAMPLE_LIMIT);

	@Override
	public Set<Scope.ScopeType> supportedScopeTypes() {
		return Set.of(Scope.ScopeType.EVENT, Scope.ScopeType.ALL);
	}

	@Override
	public CheckOutcome check(Scope scope) {
		MapSqlParameterSource params = scopeParameters(scope);
		SampledViolationQueryResult result =
				SampledViolationQueryResult.query(jdbcTemplate, VIOLATION_SQL, params);
		if (result.violationCount() == 0) {
			return CheckOutcome.pass();
		}

		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("rule", "coupon_issue structural fields and status-field combination");
		detail.put("sample", result.sample());
		return CheckOutcome.fail(result.violationCount(), detail);
	}

	private MapSqlParameterSource scopeParameters(Scope scope) {
		boolean eventScope = scope.getType() == Scope.ScopeType.EVENT;
		boolean pagedAll = scope.getType() == Scope.ScopeType.ALL && scope.getEventIds() != null;
		return new MapSqlParameterSource()
				.addValue("scopeMode", eventScope ? "EVENT" : pagedAll ? "ALL_PAGE" : "ALL_GLOBAL")
				.addValue("eventId", eventScope ? scope.getEventId() : null)
				.addValue("eventIds", pagedAll ? scope.getEventIds() : List.of(-1L))
				.addValue("to", scope.getType() == Scope.ScopeType.ALL ? scope.getTo() : null);
	}
}
