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
 * CouponHistoryStructuralConsistencyCheck용 위반 주입기.
 * 가장 최근 coupon_history 행 하나를 골라 to_status를 허용되지 않는 값으로 바꿔서
 * INVALID_TO_STATUS 위반을 재현한다. status 컬럼은 DB 레벨 ENUM이 아니라 varchar이므로
 * 임의 문자열을 넣어도 제약조건 위반 없이 저장된다.
 */
@Component
@RequiredArgsConstructor
public class CouponHistoryStructuralViolationInjector implements ConsistencyViolationInjector {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	private static final String CHECK_NAME = "CouponHistoryStructuralConsistencyCheck";

	private static final String PICK_TARGET_SQL = """
			SELECT h.history_id FROM coupon_history h
			JOIN coupon_issue ci ON ci.issue_id = h.issue_id
			WHERE ci.event_id = :eventId
			ORDER BY h.occurred_at DESC, h.history_id DESC
			LIMIT 1
			""";

	private static final String CORRUPT_SQL = """
			UPDATE coupon_history SET to_status = 'INVALID_STATUS'
			WHERE history_id = :historyId
			""";

	@Override
	public String checkName() {
		return CHECK_NAME;
	}

	@Override
	public String description() {
		return "가장 최근 coupon_history 행의 to_status를 허용되지 않는 값으로 바꿔 구조 위반을 재현합니다.";
	}

	@Override
	@Transactional
	public InjectionResult inject(Long eventId) {
		Long historyId = jdbcTemplate.query(PICK_TARGET_SQL, new MapSqlParameterSource("eventId", eventId),
				rs -> rs.next() ? rs.getLong("history_id") : null);
		if (historyId == null) {
			throw new ConsistencyCheckException(ErrorCode.INJECTION_TARGET_NOT_FOUND,
					"발급 이력이 없습니다. eventId=" + eventId);
		}
		jdbcTemplate.update(CORRUPT_SQL, new MapSqlParameterSource("historyId", historyId));
		return new InjectionResult(CHECK_NAME, eventId,
				String.format("이력 %d의 to_status를 'INVALID_STATUS'로 바꿔 구조 위반을 만들었습니다.", historyId));
	}
}
