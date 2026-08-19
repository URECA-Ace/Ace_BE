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

/** 만료 배치의 허용 지연을 넘긴 ISSUED 행과 valid_to 이후 사용된 행을 검사한다. */
@Component
@RequiredArgsConstructor
public class CouponExpirationLagConsistencyCheck implements ConsistencyCheck {

	private static final int SAMPLE_LIMIT = 20;
	private final NamedParameterJdbcTemplate jdbcTemplate;

	private static final String COUNT_SQL = """
			SELECT COUNT(*)
			FROM (
			    SELECT ci.issue_id
			    FROM coupon_issue ci
			    WHERE ci.status = 'ISSUED'
			      AND ci.valid_to <= :allowedExpirationBoundary
			      AND ci.created_at < :snapshotAt
			    UNION ALL
			    SELECT ci.issue_id
			    FROM coupon_issue ci
			    WHERE ci.status = 'USED'
			      AND ci.used_at > ci.valid_to
			      AND ci.used_at < :snapshotAt
			) violation
			""";
	private static final String SAMPLE_SQL = """
			SELECT violation.issue_id, violation.event_id, violation.status,
			       violation.valid_to, violation.used_at, violation.violation_type
			FROM (
			    SELECT ci.issue_id, ci.event_id, ci.status, ci.valid_to, ci.used_at,
			           'EXPIRATION_BATCH_DELAY' AS violation_type
			    FROM coupon_issue ci
			    WHERE ci.status = 'ISSUED'
			      AND ci.valid_to <= :allowedExpirationBoundary
			      AND ci.created_at < :snapshotAt
			    UNION ALL
			    SELECT ci.issue_id, ci.event_id, ci.status, ci.valid_to, ci.used_at,
			           'USED_AFTER_EXPIRATION' AS violation_type
			    FROM coupon_issue ci
			    WHERE ci.status = 'USED'
			      AND ci.used_at > ci.valid_to
			      AND ci.used_at < :snapshotAt
			) violation
			ORDER BY violation.issue_id
			LIMIT %d
			""".formatted(SAMPLE_LIMIT);

	@Override
	public Set<Scope.ScopeType> supportedScopeTypes() {
		return Set.of(Scope.ScopeType.AS_OF_RANGE);
	}

	@Override
	public CheckOutcome check(Scope scope) {
		// AS_OF_RANGE의 from은 "검증시각 - 합의된 허용 지연시간"이다.
		// 허용시간을 Check 안에 하드코딩하지 않아 스케줄러 정책 변경과 검증 로직을 분리한다.
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("allowedExpirationBoundary", scope.getFrom())
				.addValue("snapshotAt", scope.getTo());
		Integer count = jdbcTemplate.queryForObject(COUNT_SQL, params, Integer.class);
		int violationCount = count == null ? 0 : count;
		if (violationCount == 0) {
			return CheckOutcome.pass();
		}

		List<Map<String, Object>> sample = jdbcTemplate.queryForList(SAMPLE_SQL, params);
		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("allowedExpirationBoundary", scope.getFrom());
		detail.put("snapshotAt", scope.getTo());
		detail.put("sample", sample);
		return CheckOutcome.fail(violationCount, detail);
	}
}
