package com.ace.consistency.check;

import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.ViolationTargetType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 2. 상태 머신 정합성 (이력 연속성 검증)
 *
 * 쿠폰 이력(coupon_history) 테이블에서 상태 전이의 체인이 끊어지거나 과거 상태로 덮어씌워진 경우를 식별합니다.
 * "현재 레코드의 출발지(from_status)는 반드시 직전 레코드의 목적지(prev_to_status)와 일치해야 한다"는
 * 불변의 법칙을 검증하여, 동시성 충돌이나 멱등성 실패, 잘못된 로직으로 인한 덮어쓰기를 모두 잡아냅니다.
 *
 * from_status/to_status 쌍 자체가 허용된 전이인지(예: USED -> EXPIRED 같은 무효 전이)는
 * {@link CouponHistoryStructuralConsistencyCheck}의 책임이라 여기서는 다루지 않는다 —
 * 두 Check의 검증 범위가 겹치지 않도록 분리했다.
 */
@Component
@RequiredArgsConstructor
public class StateMachineConsistencyCheck implements ConsistencyCheck {
	@Override
	public String getLabel() {
		return "상태 전이 연속성 검사";
	}

	private final NamedParameterJdbcTemplate jdbcTemplate;

	private static final String SCOPE_CONDITION = """
			(
				(:scopeMode = 'EVENT' AND sub.event_id = :eventId)
				OR (:scopeMode = 'ALL' AND sub.event_id IN (:eventIds) AND sub.created_at < :to)
			)
			""";

	private static final String SQL = """
            SELECT sub.issue_id, sub.event_id AS eventId, sub.prev_to_status, sub.from_status, sub.to_status,
                   COUNT(*) OVER() AS total_violation_count
            FROM (
                SELECT ch.issue_id, 
                       ch.from_status, 
                       ch.to_status,
                       LAG(ch.to_status) OVER (PARTITION BY ch.issue_id ORDER BY ch.occurred_at, ch.history_id) as prev_to_status,
                       ROW_NUMBER() OVER (PARTITION BY ch.issue_id ORDER BY ch.occurred_at, ch.history_id) as rn,
                       ci.event_id,
                       ci.created_at
                FROM coupon_history ch
                JOIN coupon_issue ci ON ci.issue_id = ch.issue_id
            ) sub
            WHERE %s
              AND (
                  -- 1. 첫 번째 이력은 반드시 NULL -> ISSUED 여야 함 (체인의 시작점 검증)
                  (sub.rn = 1 AND NOT (sub.from_status IS NULL AND sub.to_status = 'ISSUED'))
                  OR
                  -- 2. 두 번째 이력부터는 직전 이력과 연속성이 이어지는지만 검증한다
                  --    (from/to 쌍 자체의 유효성은 CouponHistoryStructuralConsistencyCheck의 책임)
                  (sub.rn > 1 AND NOT (sub.from_status <=> sub.prev_to_status))
              )
            ORDER BY sub.issue_id
            """.formatted(SCOPE_CONDITION);

	@Override
	public Set<Scope.ScopeType> supportedScopeTypes() {
		return Set.of(Scope.ScopeType.EVENT, Scope.ScopeType.ALL);
	}

	@Override
	public CheckOutcome check(Scope scope) {
		MapSqlParameterSource params = scopeParameters(scope);
		List<Map<String, Object>> violations = jdbcTemplate.queryForList(SQL, params);

		if (violations.isEmpty()) {
			return CheckOutcome.pass();
		}

		int violationCount = ((Number) violations.getFirst().get("total_violation_count")).intValue();
		List<Violation> violationList = new ArrayList<>(violations.size());
		for (Map<String, Object> violation : violations) {
			Map<String, Object> detail = new LinkedHashMap<>(violation);
			detail.remove("total_violation_count");
			violationList.add(new Violation(ViolationTargetType.ISSUE, ((Number) violation.get("issue_id")).longValue(), detail));
		}

		Map<String, Object> diff = new LinkedHashMap<>();
		diff.put("reason", "상태 머신 위반: 이전 이력의 도착 상태와 현재 이력의 출발 상태가 이어지지 않습니다(연속성 붕괴).");

		return CheckOutcome.fail(violationCount, diff, violationList);
	}

	private MapSqlParameterSource scopeParameters(Scope scope) {
		boolean eventScope = scope.getType() == Scope.ScopeType.EVENT;
		return new MapSqlParameterSource()
				.addValue("scopeMode", eventScope ? "EVENT" : "ALL")
				.addValue("eventId", eventScope ? scope.getEventId() : null)
				// eventIds가 빈 리스트일 경우 IN 절 SQL 문법 에러 방지를 위해 의미 없는 값(-1) 세팅
				.addValue("eventIds", eventScope ? null : (scope.getEventIds().isEmpty() ? List.of(-1L) : scope.getEventIds()))
				.addValue("to", eventScope ? null : scope.getTo());
	}
}
