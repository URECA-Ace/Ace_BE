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

class IssueHistoryTimeSyncConsistencyCheckTest extends ConsistencyCheckIntegrationTestBase {

	private IssueHistoryTimeSyncConsistencyCheck check;

	@BeforeEach
	void setUp() {
		check = new IssueHistoryTimeSyncConsistencyCheck(jdbcTemplate);
	}

	private java.util.List<Scope> createTestScopes(long eventId) {
		return java.util.List.of(
				Scope.ofEvent(eventId),
				Scope.all(java.util.List.of(eventId), java.time.LocalDateTime.now())
		);
	}

	@Test
	@DisplayName("Issue의 updated_at과 History의 occurred_at이 1초 이내면 정상")
	void passWhenTimeDifferenceIsWithinOneSecond() {
		long eventId = generateUniqueId();
		long issueId = insertDummyIssue(eventId, "2024-01-01 10:00:00");
		insertDummyHistory(issueId, null, "ISSUED", "2024-01-01 10:00:00");

		for (Scope scope : createTestScopes(eventId)) {
			CheckOutcome outcome = check.check(scope);
			assertThat(outcome.isPass()).as("Scope: %s", scope.getType()).isTrue();
		}
	}

	@Test
	@DisplayName("Issue의 updated_at과 History의 occurred_at 차이가 1초 초과면 FAIL")
	void failWhenTimeDifferenceExceedsOneSecond() {
		long eventId = generateUniqueId();
		long issueId = insertDummyIssue(eventId, "2024-01-01 10:00:00");
		insertDummyHistory(issueId, null, "ISSUED", "2024-01-01 10:00:02"); // 2초 차이

		for (Scope scope : createTestScopes(eventId)) {
			CheckOutcome outcome = check.check(scope);
			assertThat(outcome.isPass()).as("Scope: %s", scope.getType()).isFalse();
			assertThat(outcome.getViolationCount()).as("Scope: %s", scope.getType()).isEqualTo(1);
		}
	}

	@Test
	@DisplayName("복원(USED -> ISSUED) 시에는 1초 이상 차이가 나더라도 시간 검증을 생략하므로 PASS 반환")
	void passWhenRestoredEvenIfTimeDifferenceExceedsOneSecond() {
		long eventId = generateUniqueId();
		long issueId = insertDummyIssue(eventId, "2024-01-01 10:00:00");
		// 최초 발급 (10시) -> 사용 (11시) -> 복원 (12시)
		insertDummyHistory(issueId, null, "ISSUED", "2024-01-01 10:00:00");
		insertDummyHistory(issueId, "ISSUED", "USED", "2024-01-01 11:00:00");
		insertDummyHistory(issueId, "USED", "ISSUED", "2024-01-01 12:00:00"); 

		for (Scope scope : createTestScopes(eventId)) {
			CheckOutcome outcome = check.check(scope);
			assertThat(outcome.isPass()).as("Scope: %s", scope.getType()).isTrue();
		}
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

	private void insertDummyHistory(long issueId, String fromStatus, String toStatus, String occurredAt) {
		String sql = """
                INSERT INTO coupon_history (issue_id, from_status, to_status, occurred_at, recorded_at)
                VALUES (:issueId, :fromStatus, :toStatus, :occurredAt, NOW())
                """;
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("issueId", issueId)
				.addValue("fromStatus", fromStatus)
				.addValue("toStatus", toStatus)
				.addValue("occurredAt", occurredAt);
		jdbcTemplate.update(sql, params);
	}
}
