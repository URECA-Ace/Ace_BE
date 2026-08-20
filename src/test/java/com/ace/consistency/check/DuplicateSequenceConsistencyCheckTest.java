package com.ace.consistency.check;

import com.ace.consistency.common.ConsistencyCheck.CheckOutcome;
import com.ace.consistency.common.Scope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateSequenceConsistencyCheckTest extends ConsistencyCheckIntegrationTestBase {

	private DuplicateSequenceConsistencyCheck check;

	@BeforeEach
	void setUp() {
		check = new DuplicateSequenceConsistencyCheck(jdbcTemplate);
		try {
			jdbcTemplate.getJdbcOperations().execute("ALTER TABLE coupon_issue DROP INDEX uk_coupon_issue_event_sequence");
		} catch (Exception e) {
			// 이미 삭제되었거나 없는 경우 무시
		}
	}

	@Test
	@DisplayName("동일 이벤트 내에서 발급 순번이 중복되지 않으면 PASS 반환")
	void passWhenNoDuplicateSequence() {
		long eventId = generateUniqueId();
		insertDummyIssue(eventId, 1L, 1001L);
		insertDummyIssue(eventId, 2L, 1002L);

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	@DisplayName("동일 이벤트 내에서 발급 순번이 중복되면 FAIL 반환 (Race Condition 발생)")
	void failWhenDuplicateSequenceExists() {
		long eventId = generateUniqueId();
		insertDummyIssue(eventId, 1L, 1001L);
		insertDummyIssue(eventId, 1L, 1002L); // Sequence 1 duplicated

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
	}

	@Test
	@Disabled("DB schema enforces NOT NULL on issue_sequence, so this test cannot be physically simulated")
	@DisplayName("null 순번은 중복 검사에서 제외된다")
	void passWhenSequenceIsNull() {
		long eventId = generateUniqueId();
		insertDummyIssue(eventId, null, 1001L);
		insertDummyIssue(eventId, null, 1002L);

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isTrue();
	}

	private void insertDummyIssue(long eventId, Long issueSequence, long userId) {
		String sql = """
                INSERT INTO coupon_issue (event_id, user_id, issue_sequence, request_id, status, issued_at, valid_from, valid_to, created_at)
                VALUES (:eventId, :userId, :issueSequence, :requestId, 'ISSUED', NOW(), NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY), NOW())
                """;
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("eventId", eventId)
				.addValue("userId", userId)
				.addValue("issueSequence", issueSequence)
				.addValue("requestId", "req-" + generateUniqueId());
		jdbcTemplate.update(sql, params);
	}
}
