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
 * IssueHistoryTimeSyncConsistencyCheck용 위반 주입기.
 * coupon_issue의 상태 전이 시각(used_at/issued_at)을 그 상태에 대응하는 최신 coupon_history.occurred_at
 * 기준으로 1초 넘게 어긋나게 만든다.
 *
 * USED 발급 건이 있으면 used_at을 5초 미루고(뒤로 미뤄도 valid_from 이후라 구조 검사에는 걸리지 않는다),
 * 없으면 최초 발급(ISSUED, from_status가 NULL인 이력) 건의 issued_at을 5초 당긴다(앞당겨도 valid_from
 * 이전이라 구조 검사에는 걸리지 않는다).
 */
@Component
@RequiredArgsConstructor
public class IssueHistoryTimeSyncViolationInjector implements ConsistencyViolationInjector {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	private static final String CHECK_NAME = "IssueHistoryTimeSyncConsistencyCheck";

	private static final String LATEST_HISTORY_CTE = """
			WITH latest_history AS (
				SELECT issue_id, from_status, to_status,
				       ROW_NUMBER() OVER (PARTITION BY issue_id ORDER BY occurred_at DESC, history_id DESC) AS rn
				FROM coupon_history
			)
			""";

	private static final String PICK_USED_TARGET_SQL = LATEST_HISTORY_CTE + """
			SELECT ci.issue_id FROM coupon_issue ci
			JOIN latest_history lh ON lh.issue_id = ci.issue_id AND lh.rn = 1
			WHERE ci.event_id = :eventId AND ci.status = 'USED' AND lh.to_status = 'USED'
			ORDER BY ci.used_at DESC
			LIMIT 1
			""";

	private static final String PICK_ISSUED_TARGET_SQL = LATEST_HISTORY_CTE + """
			SELECT ci.issue_id FROM coupon_issue ci
			JOIN latest_history lh ON lh.issue_id = ci.issue_id AND lh.rn = 1
			WHERE ci.event_id = :eventId AND ci.status = 'ISSUED'
			  AND lh.to_status = 'ISSUED' AND lh.from_status IS NULL
			ORDER BY ci.issued_at DESC
			LIMIT 1
			""";

	private static final String DELAY_USED_AT_SQL = """
			UPDATE coupon_issue SET used_at = TIMESTAMPADD(SECOND, 5, used_at) WHERE issue_id = :issueId
			""";

	private static final String ADVANCE_ISSUED_AT_SQL = """
			UPDATE coupon_issue SET issued_at = TIMESTAMPADD(SECOND, -5, issued_at) WHERE issue_id = :issueId
			""";

	@Override
	public String checkName() {
		return CHECK_NAME;
	}

	@Override
	public String description() {
		return "발급 건의 used_at(또는 issued_at)을 history 기록 시각과 5초 어긋나게 만들어 시간 동기화 위반을 재현합니다.";
	}

	@Override
	@Transactional
	public InjectionResult inject(Long eventId) {
		MapSqlParameterSource params = new MapSqlParameterSource("eventId", eventId);

		Long usedIssueId = jdbcTemplate.query(PICK_USED_TARGET_SQL, params, rs -> rs.next() ? rs.getLong("issue_id") : null);
		if (usedIssueId != null) {
			jdbcTemplate.update(DELAY_USED_AT_SQL, new MapSqlParameterSource("issueId", usedIssueId));
			return new InjectionResult(CHECK_NAME, eventId,
					String.format("발급 건 %d의 used_at을 5초 미뤄 시간 동기화 위반을 만들었습니다.", usedIssueId));
		}

		Long issuedIssueId = jdbcTemplate.query(PICK_ISSUED_TARGET_SQL, params, rs -> rs.next() ? rs.getLong("issue_id") : null);
		if (issuedIssueId != null) {
			jdbcTemplate.update(ADVANCE_ISSUED_AT_SQL, new MapSqlParameterSource("issueId", issuedIssueId));
			return new InjectionResult(CHECK_NAME, eventId,
					String.format("발급 건 %d의 issued_at을 5초 당겨 시간 동기화 위반을 만들었습니다.", issuedIssueId));
		}

		throw new ConsistencyCheckException(ErrorCode.INJECTION_TARGET_NOT_FOUND,
				"시간 동기화 위반을 만들 수 있는 발급 건이 없습니다. eventId=" + eventId);
	}
}
