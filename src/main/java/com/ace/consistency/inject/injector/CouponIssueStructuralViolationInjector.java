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
 * CouponIssueStructuralConsistencyCheck용 위반 주입기.
 * 발급 건 하나를 골라 request_id를 UUID 형식이 아닌 값으로 바꿔 INVALID_REQUEST_ID 위반을
 * 재현한다. request_id는 issue_id를 접미사로 붙여 값을 만들기 때문에, 유니크 제약(uk_coupon_issue_request_id)에
 * 걸리지 않는다.
 */
@Component
@RequiredArgsConstructor
public class CouponIssueStructuralViolationInjector implements ConsistencyViolationInjector {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	private static final String CHECK_NAME = "CouponIssueStructuralConsistencyCheck";

	private static final String PICK_TARGET_SQL = """
			SELECT issue_id FROM coupon_issue
			WHERE event_id = :eventId
			ORDER BY created_at DESC
			LIMIT 1
			""";

	private static final String CORRUPT_SQL = """
			UPDATE coupon_issue SET request_id = CONCAT('INVALID-REQUEST-ID-', issue_id)
			WHERE issue_id = :issueId
			""";

	@Override
	public String checkName() {
		return CHECK_NAME;
	}

	@Override
	public String description() {
		return "발급 건의 request_id를 UUID 형식이 아닌 값으로 바꿔 구조 위반을 재현합니다.";
	}

	@Override
	@Transactional
	public InjectionResult inject(Long eventId) {
		Long issueId = jdbcTemplate.query(PICK_TARGET_SQL, new MapSqlParameterSource("eventId", eventId),
				rs -> rs.next() ? rs.getLong("issue_id") : null);
		if (issueId == null) {
			throw new ConsistencyCheckException(ErrorCode.INJECTION_TARGET_NOT_FOUND,
					"발급 건이 없습니다. eventId=" + eventId);
		}
		jdbcTemplate.update(CORRUPT_SQL, new MapSqlParameterSource("issueId", issueId));
		return new InjectionResult(CHECK_NAME, eventId,
				String.format("발급 건 %d의 request_id를 UUID 형식이 아닌 값으로 바꿔 구조 위반을 만들었습니다.", issueId));
	}
}
