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

class ConcurrentStatusRaceCheckTest extends CheckIntegrationTestBase {

	private ConcurrentStatusRaceCheck check;

	@BeforeEach
	void setUp() {
		check = new ConcurrentStatusRaceCheck(jdbcTemplate);
	}

	@Test
	@DisplayName("동일 상태(예: USED) 변경 이력이 1회뿐이면 PASS 반환")
	void passWhenStatusIsUpdatedOnce() {
		long eventId = generateUniqueId();
		long issueId = insertDummyIssue(eventId);
		insertDummyHistory(issueId, "ISSUED", "USED");

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	@DisplayName("동일 상태(예: USED) 변경 이력이 중복되면 FAIL 반환 (Lost Update)")
	void failWhenStatusUpdateIsDuplicated() {
		long eventId = generateUniqueId();
		long issueId = insertDummyIssue(eventId);
		insertDummyHistory(issueId, "ISSUED", "USED");
		insertDummyHistory(issueId, "ISSUED", "USED"); // Concurrent race condition happened!

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
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

	private void insertDummyHistory(long issueId, String fromStatus, String toStatus) {
		String sql = """
                INSERT INTO coupon_history (issue_id, from_status, to_status, occurred_at, recorded_at)
                VALUES (:issueId, :fromStatus, :toStatus, NOW(), NOW())
                """;
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("issueId", issueId)
				.addValue("fromStatus", fromStatus)
				.addValue("toStatus", toStatus);
		jdbcTemplate.update(sql, params);
	}
}
