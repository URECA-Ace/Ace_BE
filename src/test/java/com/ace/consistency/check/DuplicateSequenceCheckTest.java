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
class DuplicateSequenceCheckTest {

	@Mock
	private NamedParameterJdbcTemplate jdbcTemplate;

	private DuplicateSequenceCheck check;

	@BeforeEach
	void setUp() {
		check = new DuplicateSequenceCheck(jdbcTemplate);
	}

	@Test
	@DisplayName("동일 이벤트 내에서 중복된 issue_sequence가 없으면 PASS 반환")
	void passWhenNoDuplicateSequence() {
		long eventId = 100L;
		when(jdbcTemplate.queryForList(anyString(), any(MapSqlParameterSource.class)))
				.thenReturn(Collections.emptyList());

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	@DisplayName("동일 이벤트 내에서 중복된 issue_sequence가 발견되면 FAIL 반환")
	void failWhenDuplicateSequenceExists() {
		long eventId = 100L;
		// DB의 Unique Constraint(uk_coupon_issue_event_sequence)로 인해 실제 INSERT 테스트가 불가능하므로 Mock 사용
		when(jdbcTemplate.queryForList(anyString(), any(MapSqlParameterSource.class)))
				.thenReturn(List.of(
						Map.of("event_id", eventId, "issue_sequence", 1L, "sequence_count", 2L)
				));

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
	}
}
