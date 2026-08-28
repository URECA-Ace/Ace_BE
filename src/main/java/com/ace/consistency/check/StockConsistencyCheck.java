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
 * 재고 정합성 검사.
 *
 * 검증 기준 (CANCELED는 슬롯을 반납한 상태이므로 재고 계산에서 제외):
 *   - total_stock = 활성 발급 건수(ISSUED+USED+EXPIRED) + remaining_stock
 *   - issued_quantity = 활성 발급 건수(ISSUED+USED+EXPIRED)
 *
 * EVENT 스코프: 특정 event_id 하나만 검사 (WHERE 절에 event_id 필터 추가)
 * ALL 스코프: 마감된 회차(SOLD_OUT / CLOSED)만 검사
 *
 * 마감된 회차는 :to 컷오프 없이 전체 발급 건수와 비교
 * issued_quantity 는 마감 시점의 최신 확정 수인데 컷오프를 걸면 직전 safety margin 구간의
 * 발급이 COUNT 에서만 빠져 오탐이 난다. 마감 이후에는 새로 저장되는 건이 없어 안전하다.
 */
@Component
@RequiredArgsConstructor
public class StockConsistencyCheck implements ConsistencyCheck {
	@Override
	public String getLabel() {
		return "재고 정합성 검사";
	}

	private final NamedParameterJdbcTemplate jdbcTemplate;

	private static final String ALL_SQL = """
			SELECT ce.event_id,
				   ce.total_stock,
				   ce.issued_quantity,
				   ce.remaining_stock,
				   COUNT(CASE WHEN ci.status IN ('ISSUED','USED','EXPIRED') THEN 1 END) AS actual_active_count
			FROM coupon_event ce
			LEFT JOIN coupon_issue ci FORCE INDEX (idx_coupon_issue_event_user_status_created)
				   ON ci.event_id = ce.event_id
			WHERE ce.event_id IN (:eventIds)
			  AND ce.status IN ('SOLD_OUT','CLOSED')
			GROUP BY ce.event_id, ce.total_stock, ce.issued_quantity, ce.remaining_stock
			HAVING ce.total_stock != actual_active_count + ce.remaining_stock
				OR ce.issued_quantity != actual_active_count
            """;

	private static final String EVENT_SQL = """
			SELECT ce.event_id,
				   ce.total_stock,
				   ce.issued_quantity,
				   ce.remaining_stock,
				   COUNT(CASE WHEN ci.status IN ('ISSUED','USED','EXPIRED') THEN 1 END) AS actual_active_count
			FROM coupon_event ce
			LEFT JOIN coupon_issue ci
				   ON ci.event_id = ce.event_id
			WHERE ce.event_id IN (:eventId)
			GROUP BY ce.event_id, ce.total_stock, ce.issued_quantity, ce.remaining_stock
			HAVING ce.total_stock != actual_active_count + ce.remaining_stock
				OR ce.issued_quantity != actual_active_count
            """;


	@Override
	public Set<Scope.ScopeType> supportedScopeTypes() {
		return Set.of(Scope.ScopeType.EVENT, Scope.ScopeType.ALL);
	}

	@Override
	public CheckOutcome check(Scope scope) {
		List<Map<String, Object>> violations;
		switch(scope.getType()){
			case EVENT -> {
				Long eventIdFilter = scope.getEventId();
				MapSqlParameterSource param = new MapSqlParameterSource("eventId", eventIdFilter);
				violations = jdbcTemplate.queryForList(EVENT_SQL, param);
			}
			case ALL -> {
				List<Long> eventIds = scope.getEventIds();
				// 마감된 회차만 검사하므로 :to 컷오프를 쓰지 않는다. ALL_SQL 주석 참조
				MapSqlParameterSource param = new MapSqlParameterSource()
						.addValue("eventIds", eventIds);
				violations = jdbcTemplate.queryForList(ALL_SQL, param); // ALL_SQL로 수정
			}
			default -> throw new IllegalArgumentException("Unsupported scope type: " + scope.getType());
		}

		if (violations.isEmpty()) {
			return CheckOutcome.pass();
		}

		return CheckOutcome.fail(violations.size(), buildDiffDetail(violations));
	}

	private Map<String, Object> buildDiffDetail(List<Map<String, Object>> violations) {
		Map<String, Object> diff = new LinkedHashMap<>();
		diff.put("violationCount", violations.size());
		diff.put("sample", violations.stream()
				.map(row -> Map.of(
						"eventId", row.get("event_id"),
						"totalStock", row.get("total_stock"),
						"issuedQuantity", row.get("issued_quantity"),
						"remainingStock", row.get("remaining_stock"),
						"actualActiveCount", row.get("actual_active_count")
				))
				.toList());
		return diff;
	}
}
