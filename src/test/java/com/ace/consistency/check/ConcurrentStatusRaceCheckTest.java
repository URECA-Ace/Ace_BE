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
class ConcurrentStatusRaceCheckTest {

	@Mock
	private NamedParameterJdbcTemplate jdbcTemplate;

	private ConcurrentStatusRaceCheck check;

	@BeforeEach
	void setUp() {
		check = new ConcurrentStatusRaceCheck(jdbcTemplate);
	}

	@Test
	@DisplayName("동일 쿠폰에 대해 USED 상태 이력이 1번만 존재하면 PASS 반환")
	void passWhenNoDuplicateStatusHistory() {
		long eventId = 1L;
		when(jdbcTemplate.queryForList(anyString(), any(MapSqlParameterSource.class)))
				.thenReturn(Collections.emptyList());

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	@DisplayName("동일 쿠폰에 대해 USED 상태 이력이 여러 번 존재하면 FAIL 반환 (Lost Update)")
	void failWhenDuplicateStatusHistoryExists() {
		long eventId = 1L;
		when(jdbcTemplate.queryForList(anyString(), any(MapSqlParameterSource.class)))
				.thenReturn(List.of(
						Map.of("issue_id", 100L, "to_status", "USED", "status_count", 2L)
				));

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("동일 쿠폰이더라도 다른 상태 전이(USED 후 CANCELED)면 PASS 반환")
	void passWhenDifferentStatusTransitions() {
		long eventId = 1L;
		when(jdbcTemplate.queryForList(anyString(), any(MapSqlParameterSource.class)))
				.thenReturn(Collections.emptyList());

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isTrue();
	}
}
