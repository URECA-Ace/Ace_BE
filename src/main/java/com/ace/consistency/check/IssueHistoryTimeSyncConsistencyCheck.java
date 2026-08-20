package com.ace.consistency.check;

import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.Scope;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 4. 연동 도메인 정합성 (Issue vs History 시간 동기화 검증)
 *
 * 태연님이 작성하신 상태(Status) 검증에 더하여, 두 테이블 간의 **상태 전이 시간(Timestamp)**이 정확히 동기화되어 있는지 검증합니다.
 * 트랜잭션 분리나 비동기 처리 지연으로 인해 coupon_issue의 시간(used_at 등)과 coupon_history의 시간(occurred_at)이
 * 1초(허용 오차) 이상 크게 벌어지는 원자성(Atomicity) 붕괴 현상을 식별합니다.
 */
@Component
@RequiredArgsConstructor
public class IssueHistoryTimeSyncConsistencyCheck implements ConsistencyCheck {

	private static final int SAMPLE_LIMIT = 20;
	// 배치가 돌지 않았다고 간주하는 최대 허용 지연 시간 (초) - 기본값 24시간
	private static final int MAX_BATCH_LAG_SECONDS = 86400; 

	private final NamedParameterJdbcTemplate jdbcTemplate;

	// 상태는 일치하지만, 시간이 1초 이상 차이나는 경우를 검출
	// 상태는 일치하지만, 시간이 누락되었거나 1초 이상 차이나는 경우를 검출
	private static final String SQL = """
            SELECT ci.issue_id, ci.status, 
                   CASE ci.status 
                       WHEN 'USED' THEN ci.used_at 
                       WHEN 'CANCELED' THEN ci.canceled_at 
                       WHEN 'ISSUED' THEN ci.issued_at 
                       WHEN 'EXPIRED' THEN ci.valid_to
                   END as issue_time,
                   latest_history.occurred_at as history_time,
                   ABS(TIMESTAMPDIFF(MICROSECOND, 
                       CASE ci.status 
                           WHEN 'USED' THEN ci.used_at 
                           WHEN 'CANCELED' THEN ci.canceled_at 
                           WHEN 'ISSUED' THEN ci.issued_at 
                           WHEN 'EXPIRED' THEN ci.valid_to
                       END, 
                       latest_history.occurred_at
                   )) / 1000000.0 as time_diff_seconds
            FROM coupon_issue ci
            JOIN (
                SELECT issue_id, to_status, occurred_at,
                       ROW_NUMBER() OVER(PARTITION BY issue_id ORDER BY occurred_at DESC, history_id DESC) as rn
                FROM coupon_history
            ) latest_history ON ci.issue_id = latest_history.issue_id AND latest_history.rn = 1
            WHERE (:eventId IS NULL OR ci.event_id = :eventId)
              AND ci.status = latest_history.to_status
              AND ci.status IN ('ISSUED', 'USED', 'CANCELED', 'EXPIRED')
              AND (
                  -- 1) 리뷰 반영: 기준 시간이 누락된 경우 즉시 위반 처리
                  CASE ci.status 
                       WHEN 'USED' THEN ci.used_at 
                       WHEN 'CANCELED' THEN ci.canceled_at 
                       WHEN 'ISSUED' THEN ci.issued_at 
                       WHEN 'EXPIRED' THEN ci.valid_to
                  END IS NULL
                  
                  OR 
                  
                  -- 2) 시간 차이 검증 (상태별 특성 반영)
                  (
                      -- [실시간 처리] ISSUED, USED, CANCELED는 1초 오차만 허용
                      ci.status IN ('ISSUED', 'USED', 'CANCELED') AND
                      ABS(TIMESTAMPDIFF(MICROSECOND, 
                           CASE ci.status 
                               WHEN 'USED' THEN ci.used_at 
                               WHEN 'CANCELED' THEN ci.canceled_at 
                               WHEN 'ISSUED' THEN ci.issued_at 
                           END, 
                           latest_history.occurred_at)) > 1000000
                  )
                  OR
                  (
                      -- [배치 처리] EXPIRED는 스케줄러 지연(Lag)을 고려하여 별도 검증
                      ci.status = 'EXPIRED' AND
                      (
                          -- 유효기간 전에 미리 만료시킨 경우 (치명적 버그)
                          latest_history.occurred_at < ci.valid_to
                          OR
                          -- 배치가 유효기간 만료 후 너무 늦게 도는 경우 (스케줄러 장애 의심)
                          TIMESTAMPDIFF(SECOND, ci.valid_to, latest_history.occurred_at) > :maxBatchLagSeconds
                      )
                  )
              )
            """;

	@Override
	public Set<Scope.ScopeType> supportedScopeTypes() {
		return Set.of(Scope.ScopeType.EVENT, Scope.ScopeType.ALL);
	}

	@Override
	public CheckOutcome check(Scope scope) {
		Long eventIdFilter = scope.getType() == Scope.ScopeType.EVENT ? scope.getEventId() : null;
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("eventId", eventIdFilter)
				.addValue("maxBatchLagSeconds", MAX_BATCH_LAG_SECONDS);

		List<Map<String, Object>> violations = jdbcTemplate.queryForList(SQL, params);

		if (violations.isEmpty()) {
			return CheckOutcome.pass();
		}

		Map<String, Object> diff = new LinkedHashMap<>();
		diff.put("sample", violations.stream()
				.limit(SAMPLE_LIMIT)
				.map(row -> Map.of(
						"issueId", row.get("issue_id"),
						"status", row.get("status"),
						"issueTime", row.get("issue_time"),
						"historyTime", row.get("history_time"),
						"timeDiffSeconds", row.get("time_diff_seconds")
				)).toList());
		diff.put("reason", "연동 도메인 시간 동기화 위반: coupon_issue와 coupon_history 간의 상태 변경 시간이 1초 이상 불일치합니다. (트랜잭션 원자성 의심)");

		return CheckOutcome.fail(violations.size(), diff);
	}
}
