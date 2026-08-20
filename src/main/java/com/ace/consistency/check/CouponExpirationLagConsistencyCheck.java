package com.ace.consistency.check;

import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.Scope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
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
	private final Clock clock;

	@Autowired
	public CouponExpirationLagConsistencyCheck(
			NamedParameterJdbcTemplate jdbcTemplate,
			Environment environment) {
		this(jdbcTemplate,
				() -> environment.getRequiredProperty(ALLOWED_DELAY_PROPERTY, Duration.class),
				Clock.systemDefaultZone());
	}

	CouponExpirationLagConsistencyCheck(
			NamedParameterJdbcTemplate jdbcTemplate,
			Duration allowedDelay) {
		this(jdbcTemplate, () -> allowedDelay, Clock.systemDefaultZone());
	}

	CouponExpirationLagConsistencyCheck(
			NamedParameterJdbcTemplate jdbcTemplate,
			Duration allowedDelay,
			Clock clock) {
		this(jdbcTemplate, () -> allowedDelay, clock);
	}

	private CouponExpirationLagConsistencyCheck(
			NamedParameterJdbcTemplate jdbcTemplate,
			Supplier<Duration> allowedDelayProvider,
			Clock clock) {
		this.jdbcTemplate = jdbcTemplate;
		this.allowedDelayProvider = allowedDelayProvider;
		this.clock = clock;
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
			      AND ci.valid_to >= :rangeFrom
			      AND ci.valid_to < :rangeTo
			      AND TIMESTAMPADD(MICROSECOND, :allowedDelayMicros, ci.valid_to) < :checkedAt
			      AND ci.created_at <= :checkedAt
			    UNION ALL
			    SELECT ci.issue_id
			    FROM coupon_issue ci
			    WHERE ci.status = 'USED'
			      AND ci.used_at > ci.valid_to
			      AND ci.valid_to >= :rangeFrom
			      AND ci.valid_to < :rangeTo
			      AND ci.used_at <= :checkedAt
			) violation
			""";
	private static final String EVENT_COUNT_SQL = """
			SELECT COUNT(*)
			FROM (
			    SELECT ci.issue_id
			    FROM coupon_issue ci
			    WHERE ci.event_id = :eventId
			      AND ci.status = 'ISSUED'
			      AND TIMESTAMPADD(MICROSECOND, :allowedDelayMicros, ci.valid_to) < :checkedAt
			      AND ci.created_at <= :checkedAt
			    UNION ALL
			    SELECT ci.issue_id
			    FROM coupon_issue ci
			    WHERE ci.event_id = :eventId
			      AND ci.status = 'USED'
			      AND ci.used_at > ci.valid_to
			      AND ci.used_at <= :checkedAt
			) violation
			""";
	private static final String ALL_COUNT_SQL = """
			SELECT COUNT(*)
			FROM (
			    SELECT ci.issue_id
			    FROM coupon_issue ci
			    WHERE ci.event_id IN (:eventIds)
			      AND ci.status = 'ISSUED'
			      AND TIMESTAMPADD(MICROSECOND, :allowedDelayMicros, ci.valid_to) < :checkedAt
			      AND ci.created_at < :checkedAt
			    UNION ALL
			    SELECT ci.issue_id
			    FROM coupon_issue ci
			    WHERE ci.event_id IN (:eventIds)
			      AND ci.status = 'USED'
			      AND ci.used_at > ci.valid_to
			      AND ci.used_at < :checkedAt
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
			      AND ci.valid_to >= :rangeFrom
			      AND ci.valid_to < :rangeTo
			      AND TIMESTAMPADD(MICROSECOND, :allowedDelayMicros, ci.valid_to) < :checkedAt
			      AND ci.created_at <= :checkedAt
			    UNION ALL
			    SELECT ci.issue_id, ci.event_id, ci.status, ci.valid_to, ci.used_at,
			           'USED_AFTER_EXPIRATION' AS violation_type
			    FROM coupon_issue ci
			    WHERE ci.status = 'USED'
			      AND ci.used_at > ci.valid_to
			      AND ci.valid_to >= :rangeFrom
			      AND ci.valid_to < :rangeTo
			      AND ci.used_at <= :checkedAt
			) violation
			ORDER BY violation.issue_id
			LIMIT %d
			""".formatted(SAMPLE_LIMIT);
	private static final String EVENT_SAMPLE_SQL = """
			SELECT violation.issue_id, violation.event_id, violation.status,
			       violation.valid_to, violation.used_at, violation.violation_type
			FROM (
			    SELECT ci.issue_id, ci.event_id, ci.status, ci.valid_to, ci.used_at,
			           'EXPIRATION_BATCH_DELAY' AS violation_type
			    FROM coupon_issue ci
			    WHERE ci.event_id = :eventId
			      AND ci.status = 'ISSUED'
			      AND TIMESTAMPADD(MICROSECOND, :allowedDelayMicros, ci.valid_to) < :checkedAt
			      AND ci.created_at <= :checkedAt
			    UNION ALL
			    SELECT ci.issue_id, ci.event_id, ci.status, ci.valid_to, ci.used_at,
			           'USED_AFTER_EXPIRATION' AS violation_type
			    FROM coupon_issue ci
			    WHERE ci.event_id = :eventId
			      AND ci.status = 'USED'
			      AND ci.used_at > ci.valid_to
			      AND ci.used_at <= :checkedAt
			) violation
			ORDER BY violation.issue_id
			LIMIT %d
			""".formatted(SAMPLE_LIMIT);
	private static final String ALL_SAMPLE_SQL = """
			SELECT violation.issue_id, violation.event_id, violation.status,
			       violation.valid_to, violation.used_at, violation.violation_type
			FROM (
			    SELECT ci.issue_id, ci.event_id, ci.status, ci.valid_to, ci.used_at,
			           'EXPIRATION_BATCH_DELAY' AS violation_type
			    FROM coupon_issue ci
			    WHERE ci.event_id IN (:eventIds)
			      AND ci.status = 'ISSUED'
			      AND TIMESTAMPADD(MICROSECOND, :allowedDelayMicros, ci.valid_to) < :checkedAt
			      AND ci.created_at < :checkedAt
			    UNION ALL
			    SELECT ci.issue_id, ci.event_id, ci.status, ci.valid_to, ci.used_at,
			           'USED_AFTER_EXPIRATION' AS violation_type
			    FROM coupon_issue ci
			    WHERE ci.event_id IN (:eventIds)
			      AND ci.status = 'USED'
			      AND ci.used_at > ci.valid_to
			      AND ci.used_at < :checkedAt
			) violation
			ORDER BY violation.issue_id
			LIMIT %d
			""".formatted(SAMPLE_LIMIT);

	@Override
	public Set<Scope.ScopeType> supportedScopeTypes() {
		return Set.of(Scope.ScopeType.EVENT, Scope.ScopeType.AS_OF_RANGE, Scope.ScopeType.ALL);
	}

	@Override
	public CheckOutcome check(Scope scope) {
		Duration allowedDelay = resolveAllowedDelay();
		LocalDateTime checkedAt = scope.getType() == Scope.ScopeType.ALL
				? scope.getTo()
				: LocalDateTime.now(clock);
		// Scope는 valid_to 기준의 검증 대상 구간만 결정한다.
		// 선택된 행의 만료 처리 지연 여부는 고정된 checkedAt과 별도로 비교한다.
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("checkedAt", checkedAt)
				.addValue("allowedDelayMicros", allowedDelay.toNanos() / 1_000L);
		String countSql;
		String sampleSql;
		switch (scope.getType()) {
			case EVENT -> {
				params.addValue("eventId", scope.getEventId());
				countSql = EVENT_COUNT_SQL;
				sampleSql = EVENT_SAMPLE_SQL;
			}
			case AS_OF_RANGE -> {
				params.addValue("rangeFrom", scope.getFrom())
						.addValue("rangeTo", scope.getTo());
				countSql = COUNT_SQL;
				sampleSql = SAMPLE_SQL;
			}
			case ALL -> {
				params.addValue("eventIds", scope.getEventIds());
				countSql = ALL_COUNT_SQL;
				sampleSql = ALL_SAMPLE_SQL;
			}
			default -> throw new IllegalArgumentException("Unsupported scope type: " + scope.getType());
		}

		Integer count = jdbcTemplate.queryForObject(countSql, params, Integer.class);
		int violationCount = count == null ? 0 : count;
		if (violationCount == 0) {
			return CheckOutcome.pass();
		}

		List<Map<String, Object>> sample = jdbcTemplate.queryForList(sampleSql, params);
		Map<String, Object> detail = new LinkedHashMap<>();
		switch (scope.getType()) {
			case EVENT -> detail.put("eventId", scope.getEventId());
			case AS_OF_RANGE -> {
				detail.put("rangeFrom", scope.getFrom());
				detail.put("rangeTo", scope.getTo());
			}
			case ALL -> detail.put("eventIds", scope.getEventIds());
		}
		detail.put("checkedAt", checkedAt);
		detail.put("allowedDelay", allowedDelay.toString());
		detail.put("sample", sample);
		return CheckOutcome.fail(violationCount, detail);
	}
}
