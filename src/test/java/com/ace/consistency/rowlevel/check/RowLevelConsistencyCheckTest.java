package com.ace.consistency.rowlevel.check;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RowLevelConsistencyCheckTest {

	private final NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);

	@Test
	void 구조_위반이_없으면_통과한다() {
		when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
				.thenReturn(0);

		CheckOutcome outcome = new CouponIssueStructuralConsistencyCheck(jdbcTemplate).check(Scope.all());

		assertThat(outcome.isPass()).isTrue();
		assertThat(outcome.getViolationCount()).isZero();
	}

	@Test
	void 현재_상태와_최신_이력이_다르면_실패_상세를_반환한다() {
		when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
				.thenReturn(1);
		when(jdbcTemplate.queryForList(anyString(), any(MapSqlParameterSource.class)))
				.thenReturn(List.of(Map.of(
						"issue_id", 10L,
						"current_status", "USED",
						"to_status", "ISSUED")));

		CheckOutcome outcome = new CouponIssueHistoryStateConsistencyCheck(jdbcTemplate).check(Scope.all());

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
		assertThat(outcome.getDiffDetail()).containsKey("sample");
	}

	@Test
	void 만료_시차_검증은_호출자가_정한_허용_경계를_사용한다() {
		LocalDateTime boundary = LocalDateTime.of(2026, 8, 14, 12, 55);
		Scope scope = Scope.ofAsOfRange(boundary, boundary.plusMinutes(5));
		when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
				.thenReturn(0);

		CheckOutcome outcome = new CouponExpirationLagConsistencyCheck(jdbcTemplate).check(scope);

		assertThat(outcome.isPass()).isTrue();
		verify(jdbcTemplate).queryForObject(anyString(),
				any(MapSqlParameterSource.class), eq(Integer.class));
	}

	@Test
	void 이력_구조_검증은_이벤트와_전체_범위를_지원한다() {
		CouponHistoryStructuralConsistencyCheck check =
				new CouponHistoryStructuralConsistencyCheck(jdbcTemplate);

		assertThat(check.supportedScopeTypes())
				.containsExactlyInAnyOrder(Scope.ScopeType.EVENT, Scope.ScopeType.ALL);
	}
}
