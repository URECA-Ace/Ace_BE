package com.ace.consistency.inject.injector;

import java.time.LocalDateTime;

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
 * CouponIssueHistoryStateConsistencyCheck용 위반 주입기.
 * 발급 건 하나를 골라 coupon_issue.status만 직접 바꿔치기해서, coupon_history에는 남지 않은
 * 상태 변화를 만든다 — coupon_issue.status와 가장 최근 coupon_history.to_status가
 * 어긋나는(LATEST_STATUS_MISMATCH) 상황을 재현한다.
 *
 * ISSUED <-> USED만 서로 뒤바꾸고 used_at/canceled_at도 새 status에 맞게 같이 정리한다 —
 * 그래야 CouponIssueStructuralConsistencyCheck의 status-필드 조합 규칙까지 함께 깨지 않고,
 * 이 체크 하나만 정확히 위반시킬 수 있다.
 */
@Component
@RequiredArgsConstructor
public class CouponIssueHistoryStateViolationInjector implements ConsistencyViolationInjector {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	private static final String CHECK_NAME = "CouponIssueHistoryStateConsistencyCheck";

	private static final String PICK_TARGET_SQL = """
			SELECT issue_id, status FROM coupon_issue
			WHERE event_id = :eventId
			ORDER BY created_at DESC
			LIMIT 1
			""";

	private static final String CORRUPT_SQL = """
			UPDATE coupon_issue
			SET status = :newStatus, used_at = :usedAt, canceled_at = :canceledAt
			WHERE issue_id = :issueId
			""";

	@Override
	public String checkName() {
		return CHECK_NAME;
	}

	@Override
	public String description() {
		return "발급 건의 coupon_issue.status를 history와 무관하게 바꿔치기해 상태 불일치를 재현합니다.";
	}

	@Override
	@Transactional
	public InjectionResult inject(Long eventId) {
		Target target = jdbcTemplate.query(PICK_TARGET_SQL, new MapSqlParameterSource("eventId", eventId),
				rs -> rs.next() ? new Target(rs.getLong("issue_id"), rs.getString("status")) : null);
		if (target == null) {
			throw new ConsistencyCheckException(ErrorCode.INJECTION_TARGET_NOT_FOUND,
					"발급 건이 없습니다. eventId=" + eventId);
		}

		boolean wasIssued = "ISSUED".equals(target.status());
		String newStatus = wasIssued ? "USED" : "ISSUED";
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("issueId", target.issueId())
				.addValue("newStatus", newStatus)
				.addValue("usedAt", wasIssued ? LocalDateTime.now() : null)
				.addValue("canceledAt", null);
		jdbcTemplate.update(CORRUPT_SQL, params);

		return new InjectionResult(CHECK_NAME, eventId,
				String.format("발급 건 %d의 status를 %s -> %s(history 반영 없이)로 바꿔 상태 불일치를 만들었습니다.",
						target.issueId(), target.status(), newStatus));
	}

	private record Target(Long issueId, String status) {
	}
}
