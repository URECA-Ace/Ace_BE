package com.ace.consistency.check;

import com.ace.consistency.common.ConsistencyCheck.CheckOutcome;
import com.ace.consistency.common.Scope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IssueHistoryTimeSyncCheckTest {

	@Mock
	private NamedParameterJdbcTemplate jdbcTemplate;

	private IssueHistoryTimeSyncCheck check;

	@BeforeEach
	void setUp() {
		check = new IssueHistoryTimeSyncCheck(jdbcTemplate);
	}

	@Test
	@DisplayName("coupon_issue의 상태변경시간과 history의 기록시간이 1초 이내면 PASS 반환")
	void passWhenTimeDifferenceIsWithin1Second() {
		long eventId = 1L;
		when(jdbcTemplate.queryForList(anyString(), any(MapSqlParameterSource.class)))
				.thenReturn(Collections.emptyList());

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	@DisplayName("coupon_issue의 상태변경시간과 history의 기록시간이 1초 초과 차이나면 FAIL 반환")
	void failWhenTimeDifferenceExceeds1Second() {
		long eventId = 1L;
		when(jdbcTemplate.queryForList(anyString(), any(MapSqlParameterSource.class)))
				.thenReturn(List.of(
						Map.of("issue_id", 100L, "status", "USED", "issue_time", "2023-01-01 10:00:00", "history_time", "2023-01-01 10:00:02", "time_diff_seconds", 2.0)
				));

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("상태가 일치하지 않는 경우(이미 다른 검증기에서 걸러짐)는 시간 검증을 패스한다")
	void passWhenStatusDoesNotMatch() {
		long eventId = 1L;
		when(jdbcTemplate.queryForList(anyString(), any(MapSqlParameterSource.class)))
				.thenReturn(Collections.emptyList());

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isTrue();
	}
}
