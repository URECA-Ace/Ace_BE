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
 * 4. 연동 도메인 정합성 (Issue vs History 시간 동기화 검증)
 *
 * CouponIssueHistoryStateConsistencyCheck의 상태(Status) 검증에 더하여, 두 테이블 간의 **상태 전이 시간(Timestamp)**이 정확히 동기화되어 있는지 검증합니다.
 * 트랜잭션 분리나 비동기 처리 지연으로 인해 coupon_issue의 시간(used_at 등)과 coupon_history의 시간(occurred_at)이
 * 1초(허용 오차) 이상 크게 벌어지는 원자성(Atomicity) 붕괴 현상을 식별합니다.
 */
@Component
@RequiredArgsConstructor
public class IssueHistoryTimeSyncConsistencyCheck implements ConsistencyCheck {
	private static final String MANUAL_EXPIRED_REASON = "MANUAL_EXPIRED";

	// 배치가 돌지 않았다고 간주하는 최대 허용 지연 시간 (초) - 기본값 24시간 .env로 일괄 관리예정 !!!!!
	private static final int MAX_BATCH_LAG_SECONDS = 86400; 

	private final NamedParameterJdbcTemplate jdbcTemplate;

	private static final String SELECT_CLAUSE = """
            SELECT ci.issue_id, ci.status, 
                   CASE ci.status 
                       WHEN 'USED' THEN ci.used_at 
                       WHEN 'ISSUED' THEN ci.issued_at 
                       WHEN 'EXPIRED' THEN ci.valid_to
                   END as issue_time,
                   latest_history.occurred_at as history_time,
                   ABS(TIMESTAMPDIFF(MICROSECOND, 
                       CASE ci.status 
                           WHEN 'USED' THEN ci.used_at 
                           WHEN 'ISSUED' THEN ci.issued_at 
                           WHEN 'EXPIRED' THEN ci.valid_to
                       END, 
                       latest_history.occurred_at
                   )) / 1000000.0 as time_diff_seconds,
                   COUNT(*) OVER() AS total_violation_count
            FROM coupon_issue ci
            """;

	private static final String ALL_JOIN_CLAUSE = """
            JOIN (
                SELECT issue_id, from_status, to_status, reason, occurred_at,
                       ROW_NUMBER() OVER(PARTITION BY issue_id ORDER BY occurred_at DESC, history_id DESC) as rn
                FROM coupon_history
            ) latest_history ON ci.issue_id = latest_history.issue_id AND latest_history.rn = 1
            WHERE ci.event_id IN (:eventIds) AND ci.created_at < :to
            """;

	private static final String EVENT_JOIN_CLAUSE = """
            JOIN LATERAL (
                SELECT from_status, to_status, reason, occurred_at
                FROM coupon_history ch
                WHERE ch.issue_id = ci.issue_id
                ORDER BY occurred_at DESC, history_id DESC
                LIMIT 1
            ) latest_history ON TRUE
            WHERE ci.event_id = :eventId
            """;

	private static final String FILTER_CLAUSE = """
              AND ci.status = latest_history.to_status
              AND ci.status IN ('ISSUED', 'USED', 'EXPIRED')
              AND (
                  -- 1) 리뷰 반영: 기준 시간이 누락된 경우 즉시 위반 처리
                  CASE ci.status 
                       WHEN 'USED' THEN ci.used_at 
                       WHEN 'ISSUED' THEN ci.issued_at 
                       WHEN 'EXPIRED' THEN ci.valid_to
                  END IS NULL
                  
                  OR 
                  
                  -- 2) 시간 차이 검증 (상태별 특성 반영)
                  (
                      -- [실시간 처리] 
                      -- USED는 1초 오차 허용
                      -- ISSUED는 최초 발급(from_status IS NULL)일 때만 1초 오차 허용 (USED->ISSUED 복원 건은 제외)
                      (
                          ci.status = 'USED' 
                          OR (ci.status = 'ISSUED' AND latest_history.from_status IS NULL)
                      ) AND
                      ABS(TIMESTAMPDIFF(MICROSECOND, 
                           CASE ci.status 
                               WHEN 'USED' THEN ci.used_at 
                               WHEN 'ISSUED' THEN ci.issued_at 
                           END, 
                           latest_history.occurred_at)) > 1000000
                  )
                  OR
                  (
                      -- [배치 처리] 자연 만료 EXPIRED만 스케줄러 지연(Lag)을 검증
                      ci.status = 'EXPIRED' AND
                      (latest_history.reason IS NULL OR latest_history.reason <> :manualExpiredReason) AND
                      (
                          -- 유효기간 전에 미리 만료시킨 경우 (치명적 버그)
                          latest_history.occurred_at < ci.valid_to
                          OR
                          -- 배치가 유효기간 만료 후 너무 늦게 도는 경우 (스케줄러 장애 의심)
                          TIMESTAMPDIFF(SECOND, ci.valid_to, latest_history.occurred_at) > :maxBatchLagSeconds
                      )
                  )
              )
            ORDER BY ci.issue_id
            """;

	private static final String ALL_SQL = SELECT_CLAUSE + ALL_JOIN_CLAUSE + FILTER_CLAUSE;
	private static final String EVENT_SQL = SELECT_CLAUSE + EVENT_JOIN_CLAUSE + FILTER_CLAUSE;

	@Override
	public Set<Scope.ScopeType> supportedScopeTypes() {
		return Set.of(Scope.ScopeType.EVENT, Scope.ScopeType.ALL);
	}

	@Override
	public CheckOutcome check(Scope scope) {
		MapSqlParameterSource params = scopeParameters(scope)
				.addValue("maxBatchLagSeconds", MAX_BATCH_LAG_SECONDS)
				.addValue("manualExpiredReason", MANUAL_EXPIRED_REASON);

		String sql = scope.getType() == Scope.ScopeType.EVENT ? EVENT_SQL : ALL_SQL;
		List<Map<String, Object>> violations = jdbcTemplate.queryForList(sql, params);

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
		diff.put("reason", "연동 도메인 시간 동기화 위반: coupon_issue와 coupon_history 간의 상태 변경 시간이 1초 이상 불일치합니다. (트랜잭션 원자성 의심)");

		return CheckOutcome.fail(violationCount, diff, violationList);
	}

	private MapSqlParameterSource scopeParameters(Scope scope) {
		boolean eventScope = scope.getType() == Scope.ScopeType.EVENT;
		return new MapSqlParameterSource()
				.addValue("eventId", eventScope ? scope.getEventId() : null)
				// eventIds가 빈 리스트일 경우 IN 절 SQL 문법 에러 방지를 위해 의미 없는 값(-1) 세팅
				.addValue("eventIds", eventScope ? null : (scope.getEventIds().isEmpty() ? List.of(-1L) : scope.getEventIds()))
				.addValue("to", eventScope ? null : scope.getTo());
	}
}
