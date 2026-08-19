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

/** coupon_history 자체의 필수값, 시각 순서와 허용된 상태 전이 형태를 검사한다. */
@Component
@RequiredArgsConstructor
public class CouponHistoryStructuralConsistencyCheck implements ConsistencyCheck {

	private static final int SAMPLE_LIMIT = 20;
	private final NamedParameterJdbcTemplate jdbcTemplate;

	private static final String BASE_CONDITION = """
			(:eventId IS NULL OR ci.event_id = :eventId)
			AND (
				h.issue_id IS NULL OR ci.issue_id IS NULL
				OR h.to_status IS NULL OR h.occurred_at IS NULL OR h.recorded_at IS NULL
				OR h.recorded_at < h.occurred_at
				OR h.to_status NOT IN ('ISSUED','USED','CANCELED','EXPIRED')
				OR (h.from_status IS NULL AND h.to_status <> 'ISSUED')
				OR (h.from_status IS NOT NULL AND NOT (
					h.from_status = 'ISSUED' AND h.to_status IN ('USED','CANCELED','EXPIRED')
				))
			)
			""";

	private static final String FROM_SQL =
			" FROM coupon_history h LEFT JOIN coupon_issue ci ON ci.issue_id = h.issue_id WHERE ";
	private static final String COUNT_SQL = "SELECT COUNT(*)" + FROM_SQL + BASE_CONDITION;
	private static final String SAMPLE_SQL = ("""
			SELECT h.history_id, h.issue_id, h.from_status, h.to_status, h.occurred_at, h.recorded_at
			%s%s ORDER BY h.history_id LIMIT %d
			""").formatted(FROM_SQL, BASE_CONDITION, SAMPLE_LIMIT);

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
		detail.put("rule", "coupon_history structural fields and allowed transition shape");
		detail.put("sample", sample);
		return CheckOutcome.fail(violationCount, detail);
	}

	private MapSqlParameterSource eventParameter(Scope scope) {
		Long eventId = scope.getType() == Scope.ScopeType.EVENT ? scope.getEventId() : null;
		return new MapSqlParameterSource("eventId", eventId);
	}
}
