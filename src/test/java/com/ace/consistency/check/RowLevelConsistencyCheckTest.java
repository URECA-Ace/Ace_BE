package com.ace.consistency.check;

import com.ace.consistency.common.ConsistencyCheck.CheckOutcome;
import com.ace.consistency.common.Scope;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RowLevelConsistencyCheckTest {

	private final NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);

	@Test
	void 구조_위반이_없으면_통과한다() {
		when(jdbcTemplate.queryForList(anyString(), any(MapSqlParameterSource.class)))
				.thenReturn(List.of());

		CheckOutcome outcome = new CouponIssueStructuralConsistencyCheck(jdbcTemplate)
				.check(Scope.all(LocalDateTime.now()));

		assertThat(outcome.isPass()).isTrue();
		assertThat(outcome.getViolationCount()).isZero();
	}

	@Test
	void 현재_상태와_최신_이력이_다르면_실패_상세를_반환한다() {
		when(jdbcTemplate.queryForList(anyString(), any(MapSqlParameterSource.class)))
				.thenReturn(List.of(Map.of(
						"issue_id", 10L,
						"current_status", "USED",
						"to_status", "ISSUED",
						"total_violation_count", 1L)));

		CheckOutcome outcome = new CouponIssueHistoryStateConsistencyCheck(jdbcTemplate)
				.check(Scope.all(LocalDateTime.now()));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
		assertThat(outcome.getViolations()).hasSize(1);
		assertThat(outcome.getViolations().toString())
				.doesNotContain("total_violation_count");
	}

	@Test
	void 이력_구조_검증은_이벤트와_시간_구간과_전체_범위를_지원한다() {
		CouponHistoryStructuralConsistencyCheck check =
				new CouponHistoryStructuralConsistencyCheck(jdbcTemplate);

		assertThat(check.supportedScopeTypes())
				.containsExactlyInAnyOrder(
						Scope.ScopeType.EVENT,
						Scope.ScopeType.AS_OF_RANGE,
						Scope.ScopeType.ALL);
	}

	@Test
	void 만료_시차_검증은_이벤트와_시간_구간과_전체_범위를_지원한다() {
		CouponExpirationLagConsistencyCheck check =
				new CouponExpirationLagConsistencyCheck(jdbcTemplate, 30 * 60 * 1_000L);

		assertThat(check.supportedScopeTypes())
				.containsExactlyInAnyOrder(
						Scope.ScopeType.EVENT,
						Scope.ScopeType.AS_OF_RANGE,
						Scope.ScopeType.ALL);
	}
}
