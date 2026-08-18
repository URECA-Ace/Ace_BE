package com.ace.consistency.check;

import com.ace.consistency.common.ConsistencyCheck.CheckOutcome;
import com.ace.consistency.common.Scope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import static org.assertj.core.api.Assertions.assertThat;

class IssueHistoryTimeSyncCheckTest extends CheckIntegrationTestBase {

	private IssueHistoryTimeSyncCheck check;

	@BeforeEach
	void setUp() {
		check = new IssueHistoryTimeSyncCheck(jdbcTemplate);
	}

	@Test
	@DisplayName("Issue의 updated_at과 History의 occurred_at이 1초 이내면 정상")
	void passWhenTimeDifferenceIsWithinOneSecond() {
		long eventId = generateUniqueId();
		long issueId = insertDummyIssue(eventId, "2024-01-01 10:00:00");
		insertDummyHistory(issueId, "2024-01-01 10:00:00");

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	@DisplayName("Issue의 updated_at과 History의 occurred_at 차이가 1초 초과면 FAIL")
	void failWhenTimeDifferenceExceedsOneSecond() {
		long eventId = generateUniqueId();
		long issueId = insertDummyIssue(eventId, "2024-01-01 10:00:00");
		insertDummyHistory(issueId, "2024-01-01 10:00:02"); // 2초 차이

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
	}

	private long insertDummyIssue(long eventId, String updatedAt) {
		String sql = """
                INSERT INTO coupon_issue (event_id, user_id, issue_sequence, request_id, status, issued_at, valid_from, valid_to, created_at)
                VALUES (:eventId, :userId, :issueSequence, :requestId, 'ISSUED', :updatedAt, NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY), NOW())
                """;
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("eventId", eventId)
				.addValue("userId", generateUniqueId())
				.addValue("issueSequence", 1L)
				.addValue("requestId", "req-" + generateUniqueId())
				.addValue("updatedAt", updatedAt);
		
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcTemplate.update(sql, params, keyHolder, new String[]{"issue_id"});
		return keyHolder.getKey().longValue();
	}

	private void insertDummyHistory(long issueId, String occurredAt) {
		String sql = """
                INSERT INTO coupon_history (issue_id, from_status, to_status, occurred_at, recorded_at)
                VALUES (:issueId, NULL, 'ISSUED', :occurredAt, NOW())
                """;
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("issueId", issueId)
				.addValue("occurredAt", occurredAt);
		jdbcTemplate.update(sql, params);
	}
}
