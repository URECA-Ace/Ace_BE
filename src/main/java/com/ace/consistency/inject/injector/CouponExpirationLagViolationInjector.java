package com.ace.consistency.inject.injector;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ace.common.ErrorCode;
import com.ace.common.exception.ConsistencyCheckException;
import com.ace.consistency.inject.ConsistencyViolationInjector;
import com.ace.consistency.inject.InjectionResult;

import lombok.RequiredArgsConstructor;

/**
 * CouponExpirationLagConsistencyCheck용 위반 주입기.
 * ISSUED 상태인 발급 건 하나를 골라 valid_to를 하루 전으로 당겨서, 만료 배치가 아직 처리하지
 * 못한(EXPIRATION_BATCH_DELAY) 상황을 재현한다. valid_from은 건드리지 않는다 — 발급 시점은
 * 이미 valid_to보다 과거이므로 valid_from < valid_to 구조 검사는 그대로 유지된다.
 */
@Component
@RequiredArgsConstructor
public class CouponExpirationLagViolationInjector implements ConsistencyViolationInjector {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	private static final String CHECK_NAME = "CouponExpirationLagConsistencyCheck";

	private static final String PICK_TARGET_SQL = """
			SELECT issue_id FROM coupon_issue
			WHERE event_id = :eventId AND status = 'ISSUED'
			ORDER BY created_at DESC
			LIMIT 1
			""";

	private static final String CORRUPT_SQL = """
			UPDATE coupon_issue SET valid_to = TIMESTAMPADD(DAY, -1, NOW())
			WHERE issue_id = :issueId
			""";

	@Override
	public String checkName() {
		return CHECK_NAME;
	}

	@Override
	public String description() {
		return "ISSUED 상태인 발급 건의 valid_to를 하루 전으로 당겨 만료 배치 지연을 재현합니다.";
	}

	@Override
	@Transactional
	public InjectionResult inject(Long eventId) {
		Long issueId = jdbcTemplate.query(PICK_TARGET_SQL, new MapSqlParameterSource("eventId", eventId),
						rs -> rs.next() ? rs.getLong("issue_id") : null);
		if (issueId == null) {
			throw new ConsistencyCheckException(ErrorCode.INJECTION_TARGET_NOT_FOUND,
					"ISSUED 상태인 발급 건이 없습니다. eventId=" + eventId);
		}
		jdbcTemplate.update(CORRUPT_SQL, new MapSqlParameterSource("issueId", issueId));
		return new InjectionResult(CHECK_NAME, eventId,
				String.format("발급 건 %d의 valid_to를 하루 전으로 당겨 만료 배치 지연을 만들었습니다.", issueId));
	}
}
