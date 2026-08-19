package com.ace.consistency.check;

import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.Scope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** 만료 배치의 허용 지연을 넘긴 ISSUED 행과 valid_to 이후 사용된 행을 검사한다. */
@Component
public class CouponExpirationLagConsistencyCheck implements ConsistencyCheck {

	private static final int SAMPLE_LIMIT = 20;
	private static final String ALLOWED_DELAY_PROPERTY = "consistency.expiration.allowed-delay";
	private final NamedParameterJdbcTemplate jdbcTemplate;
	private final Supplier<Duration> allowedDelayProvider;

	@Autowired
	public CouponExpirationLagConsistencyCheck(
			NamedParameterJdbcTemplate jdbcTemplate,
			Environment environment) {
		this(jdbcTemplate, () -> environment.getRequiredProperty(ALLOWED_DELAY_PROPERTY, Duration.class));
	}

	CouponExpirationLagConsistencyCheck(
			NamedParameterJdbcTemplate jdbcTemplate,
			Duration allowedDelay) {
		this(jdbcTemplate, () -> allowedDelay);
	}

	private CouponExpirationLagConsistencyCheck(
			NamedParameterJdbcTemplate jdbcTemplate,
			Supplier<Duration> allowedDelayProvider) {
		this.jdbcTemplate = jdbcTemplate;
		this.allowedDelayProvider = allowedDelayProvider;
	}

	private Duration resolveAllowedDelay() {
		Duration allowedDelay = allowedDelayProvider.get();
		if (allowedDelay.isNegative()) {
			throw new IllegalArgumentException("allowedDelay must not be negative");
		}
		return allowedDelay;
	}

	private static final String COUNT_SQL = """
			SELECT COUNT(*)
			FROM (
			    SELECT ci.issue_id
			    FROM coupon_issue ci
			    WHERE ci.status = 'ISSUED'
			      AND TIMESTAMPADD(MICROSECOND, :allowedDelayMicros, ci.valid_to) >= :rangeFrom
			      AND TIMESTAMPADD(MICROSECOND, :allowedDelayMicros, ci.valid_to) < :rangeTo
			      AND ci.created_at < :rangeTo
			    UNION ALL
			    SELECT ci.issue_id
			    FROM coupon_issue ci
			    WHERE ci.status = 'USED'
			      AND ci.used_at > ci.valid_to
			      AND ci.used_at >= :rangeFrom
			      AND ci.used_at < :rangeTo
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
			      AND TIMESTAMPADD(MICROSECOND, :allowedDelayMicros, ci.valid_to) >= :rangeFrom
			      AND TIMESTAMPADD(MICROSECOND, :allowedDelayMicros, ci.valid_to) < :rangeTo
			      AND ci.created_at < :rangeTo
			    UNION ALL
			    SELECT ci.issue_id, ci.event_id, ci.status, ci.valid_to, ci.used_at,
			           'USED_AFTER_EXPIRATION' AS violation_type
			    FROM coupon_issue ci
			    WHERE ci.status = 'USED'
			      AND ci.used_at > ci.valid_to
			      AND ci.used_at >= :rangeFrom
			      AND ci.used_at < :rangeTo
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
		Duration allowedDelay = resolveAllowedDelay();
		// AS_OF_RANGE는 원래 계약대로 [from, to) 검증 구간을 나타낸다.
		// 각 쿠폰의 만료 처리 마감 시각(valid_to + allowedDelay)이 이 구간에 들어오면 검사한다.
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("rangeFrom", scope.getFrom())
				.addValue("rangeTo", scope.getTo())
				.addValue("allowedDelayMicros", allowedDelay.toNanos() / 1_000L);
		Integer count = jdbcTemplate.queryForObject(COUNT_SQL, params, Integer.class);
		int violationCount = count == null ? 0 : count;
		if (violationCount == 0) {
			return CheckOutcome.pass();
		}

		List<Map<String, Object>> sample = jdbcTemplate.queryForList(SAMPLE_SQL, params);
		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("rangeFrom", scope.getFrom());
		detail.put("rangeTo", scope.getTo());
		detail.put("allowedDelay", allowedDelay.toString());
		detail.put("sample", sample);
		return CheckOutcome.fail(violationCount, detail);
	}
}
