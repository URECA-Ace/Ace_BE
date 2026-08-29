package com.ace.consistency.inject.injector;

import java.time.LocalDateTime;
import java.util.UUID;

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
 * StateMachineConsistencyCheck용 위반 주입기.
 * 가장 최근에 이력이 남은 발급 건을 골라, 그 건의 현재 상태로 가는 전이를 "중복으로" 한 번 더
 * 기록한다(예: 이미 USED인 건에 ISSUED->USED 이력을 한 번 더 추가). from/to 쌍 자체는
 * CouponHistoryStructuralConsistencyCheck 기준으로 허용된 조합이라 구조 검사는 통과하지만,
 * 실제 직전 이력(LAG)의 to_status와는 이어지지 않으므로 상태 머신 연속성만 정확히 위반된다 —
 * 동시성 충돌/멱등성 실패로 같은 전이가 중복 기록된 실제 장애 상황의 축소판이다.
 */
@Component
@RequiredArgsConstructor
public class StateMachineViolationInjector implements ConsistencyViolationInjector {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	private static final String CHECK_NAME = "StateMachineConsistencyCheck";

	private static final String PICK_TARGET_SQL = """
			SELECT h.issue_id, ci.status FROM coupon_history h
			JOIN coupon_issue ci ON ci.issue_id = h.issue_id
			WHERE ci.event_id = :eventId
			ORDER BY h.occurred_at DESC, h.history_id DESC
			LIMIT 1
			""";

	private static final String INSERT_DUPLICATE_HISTORY_SQL = """
			INSERT INTO coupon_history (issue_id, from_status, to_status, actor, reason, occurred_at, recorded_at, event_uid)
			VALUES (:issueId, :fromStatus, :toStatus, 'INJECTOR', 'VIOLATION_INJECTION', :now, :now, :eventUid)
			""";

	@Override
	public String checkName() {
		return CHECK_NAME;
	}

	@Override
	public String description() {
		return "발급 건의 현재 상태로 가는 전이 이력을 중복으로 한 번 더 기록해 상태 머신 연속성 붕괴를 재현합니다.";
	}

	@Override
	@Transactional
	public InjectionResult inject(Long eventId) {
		Target target = jdbcTemplate.query(PICK_TARGET_SQL, new MapSqlParameterSource("eventId", eventId),
				rs -> rs.next() ? new Target(rs.getLong("issue_id"), rs.getString("status")) : null);
		if (target == null) {
			throw new ConsistencyCheckException(ErrorCode.INJECTION_TARGET_NOT_FOUND,
					"발급 이력이 없습니다. eventId=" + eventId);
		}

		String toStatus = target.status();
		String fromStatus = "ISSUED".equals(toStatus) ? "USED" : "ISSUED";
		LocalDateTime now = LocalDateTime.now();

		jdbcTemplate.update(INSERT_DUPLICATE_HISTORY_SQL, new MapSqlParameterSource()
				.addValue("issueId", target.issueId())
				.addValue("fromStatus", fromStatus)
				.addValue("toStatus", toStatus)
				.addValue("now", now)
				.addValue("eventUid", UUID.randomUUID().toString()));

		return new InjectionResult(CHECK_NAME, eventId,
				String.format("발급 건 %d에 %s -> %s 이력을 중복 기록해 상태 전이 연속성 붕괴를 만들었습니다.",
						target.issueId(), fromStatus, toStatus));
	}

	private record Target(Long issueId, String status) {
	}
}
