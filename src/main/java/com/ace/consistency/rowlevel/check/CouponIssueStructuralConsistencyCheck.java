package com.ace.consistency.rowlevel.check;

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

	private static final String BASE_CONDITION = """
			(:eventId IS NULL OR ci.event_id = :eventId)
			AND (
				ci.event_id IS NULL OR ci.user_id IS NULL OR ci.issue_sequence IS NULL
				OR ci.issue_sequence <= 0 OR ci.request_id IS NULL OR CHAR_LENGTH(ci.request_id) <> 36
				OR (ci.message_id IS NOT NULL AND CHAR_LENGTH(ci.message_id) <> 36)
				OR ci.status IS NULL OR ci.status NOT IN ('ISSUED','USED','CANCELED','EXPIRED')
				OR ci.issued_at IS NULL OR ci.valid_from IS NULL OR ci.valid_to IS NULL OR ci.created_at IS NULL
				OR ci.issued_at > ci.valid_from OR ci.valid_from >= ci.valid_to OR ci.created_at < ci.issued_at
				OR (ci.status IN ('ISSUED','EXPIRED') AND (ci.used_at IS NOT NULL OR ci.canceled_at IS NOT NULL))
				OR (ci.status = 'USED' AND (ci.used_at IS NULL OR ci.canceled_at IS NOT NULL
					OR ci.used_at < ci.valid_from))
				OR (ci.status = 'CANCELED' AND (ci.canceled_at IS NULL OR ci.used_at IS NOT NULL
					OR ci.canceled_at < ci.issued_at))
			)
			""";

	private static final String COUNT_SQL = "SELECT COUNT(*) FROM coupon_issue ci WHERE " + BASE_CONDITION;
	private static final String SAMPLE_SQL = """
			SELECT ci.issue_id, ci.event_id, ci.user_id, ci.status,
			       ci.issued_at, ci.valid_from, ci.valid_to, ci.used_at, ci.canceled_at
			FROM coupon_issue ci
			WHERE %s
			ORDER BY ci.issue_id
			LIMIT %d
			""".formatted(BASE_CONDITION, SAMPLE_LIMIT);

	@Override
	public Set<Scope.ScopeType> supportedScopeTypes() {
		return Set.of(Scope.ScopeType.EVENT, Scope.ScopeType.ALL);
	}

	@Override
	public CheckOutcome check(Scope scope) {
		MapSqlParameterSource params = eventParameter(scope);
		Integer count = jdbcTemplate.queryForObject(COUNT_SQL, params, Integer.class);
		int violationCount = count == null ? 0 : count;
		if (violationCount == 0) {
			return CheckOutcome.pass();
		}

		List<Map<String, Object>> sample = jdbcTemplate.queryForList(SAMPLE_SQL, params);
		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("rule", "coupon_issue structural fields and status-field combination");
		detail.put("sample", sample);
		return CheckOutcome.fail(violationCount, detail);
	}

	private MapSqlParameterSource eventParameter(Scope scope) {
		Long eventId = scope.getType() == Scope.ScopeType.EVENT ? scope.getEventId() : null;
		return new MapSqlParameterSource("eventId", eventId);
	}
}
