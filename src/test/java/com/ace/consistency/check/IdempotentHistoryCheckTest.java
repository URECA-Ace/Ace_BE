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

class IdempotentHistoryCheckTest extends CheckIntegrationTestBase {

	private IdempotentHistoryCheck check;

	@BeforeEach
	void setUp() {
		check = new IdempotentHistoryCheck(jdbcTemplate);
	}

	@Test
	@DisplayName("동일 상태 전이가 2초를 초과하여 발생하면 정상으로 간주하여 PASS 반환")
	void passWhenIdempotentOverTwoSeconds() {
		long eventId = generateUniqueId();
		long issueId = insertDummyIssue(eventId);
		
		insertDummyHistory(issueId, "USED", "2024-01-01 10:00:00");
		insertDummyHistory(issueId, "USED", "2024-01-01 10:00:05"); // 5 seconds later

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	@DisplayName("동일 상태 전이가 2초 이내에 발생하면 멱등성 실패(Retry 중복)로 간주하여 FAIL 반환")
	void failWhenIdempotentWithinTwoSeconds() {
		long eventId = generateUniqueId();
		long issueId = insertDummyIssue(eventId);
		
		insertDummyHistory(issueId, "USED", "2024-01-01 10:00:00");
		insertDummyHistory(issueId, "USED", "2024-01-01 10:00:01"); // 1 second later

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("다른 상태 전이는 2초 이내라도 무시된다")
	void passWhenDifferentStatusWithinTwoSeconds() {
		long eventId = generateUniqueId();
		long issueId = insertDummyIssue(eventId);
		
		insertDummyHistory(issueId, "ISSUED", "2024-01-01 10:00:00");
		insertDummyHistory(issueId, "USED", "2024-01-01 10:00:01"); // Different status

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isTrue();
	}

	private long insertDummyIssue(long eventId) {
		String sql = """
                INSERT INTO coupon_issue (event_id, user_id, issue_sequence, request_id, status, issued_at, valid_from, valid_to, created_at)
                VALUES (:eventId, :userId, :issueSequence, :requestId, 'ISSUED', NOW(), NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY), NOW())
                """;
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("eventId", eventId)
				.addValue("userId", generateUniqueId())
				.addValue("issueSequence", 1L)
				.addValue("requestId", "req-" + generateUniqueId());
		
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcTemplate.update(sql, params, keyHolder, new String[]{"issue_id"});
		return keyHolder.getKey().longValue();
	}

	private void insertDummyHistory(long issueId, String toStatus, String occurredAt) {
		String sql = """
                INSERT INTO coupon_history (issue_id, from_status, to_status, occurred_at, recorded_at)
                VALUES (:issueId, 'ISSUED', :toStatus, :occurredAt, NOW())
                """;
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("issueId", issueId)
				.addValue("toStatus", toStatus)
				.addValue("occurredAt", occurredAt);
		jdbcTemplate.update(sql, params);
	}
}
