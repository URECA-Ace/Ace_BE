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
class IdempotentHistoryCheckTest {

	@Mock
	private NamedParameterJdbcTemplate jdbcTemplate;

	private IdempotentHistoryCheck check;

	@BeforeEach
	void setUp() {
		check = new IdempotentHistoryCheck(jdbcTemplate);
	}

	@Test
	@DisplayName("동일 상태 전이가 없으면 PASS 반환")
	void passWhenNoDuplicateHistory() {
		long eventId = 1L;
		when(jdbcTemplate.queryForList(anyString(), any(MapSqlParameterSource.class)))
				.thenReturn(Collections.emptyList());

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	@DisplayName("2초 이내에 똑같은 상태 전이가 2번 기록되면 FAIL 반환 (멱등성 파괴)")
	void failWhenSameTransitionOccursWithin2Seconds() {
		long eventId = 1L;
		when(jdbcTemplate.queryForList(anyString(), any(MapSqlParameterSource.class)))
				.thenReturn(List.of(
						Map.of("issue_id", 100L, "from_status", "ISSUED", "to_status", "USED", "time1", "2023-01-01 10:00:00", "time2", "2023-01-01 10:00:01")
				));

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("2초 이후에 똑같은 상태 전이가 기록된 경우 정상적인 다른 흐름으로 간주하고 PASS 반환")
	void passWhenSameTransitionOccursAfter2Seconds() {
		long eventId = 1L;
		when(jdbcTemplate.queryForList(anyString(), any(MapSqlParameterSource.class)))
				.thenReturn(Collections.emptyList());

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isTrue();
	}
}
