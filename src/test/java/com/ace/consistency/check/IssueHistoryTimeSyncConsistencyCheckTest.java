package com.ace.consistency.check;

import com.ace.consistency.common.ConsistencyCheck.CheckOutcome;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.ViolationTargetType;
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
		check = new IssueHistoryTimeSyncConsistencyCheck(jdbcTemplate, 1800L);
	}

	private java.util.List<Scope> createTestScopes(long eventId) {
		return java.util.List.of(
				Scope.ofEvent(eventId),
				Scope.ofAsOfRange(java.time.LocalDateTime.now().minusHours(1), java.time.LocalDateTime.now().plusHours(1)),
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
			assertThat(outcome.getViolations()).singleElement()
					.satisfies(violation -> assertThat(violation.getTargetType()).isEqualTo(ViolationTargetType.ISSUE));
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

	@Test
	@DisplayName("MANUAL_EXPIRED 수동 만료는 validTo 이전이어도 시간 정합성 검증을 통과한다")
	void passWhenManuallyExpiredBeforeValidTo() {
		long eventId = generateUniqueId();
		long issueId = insertDummyIssue(eventId, "2024-01-01 10:00:00");
		jdbcTemplate.update("""
				UPDATE coupon_issue
				SET status = 'EXPIRED', valid_to = '2024-01-08 10:00:00'
				WHERE issue_id = :issueId
				""", new MapSqlParameterSource("issueId", issueId));
		insertDummyHistory(
				issueId, "ISSUED", "EXPIRED", "MANUAL_EXPIRED", "2024-01-02 10:00:00");

		for (Scope scope : createTestScopes(eventId)) {
			CheckOutcome outcome = check.check(scope);
			assertThat(outcome.isPass()).as("Scope: %s", scope.getType()).isTrue();
		}
	}

	@Test
	@DisplayName("자연 만료 이력이 validTo 이전이면 기존 시간 정합성 위반을 유지한다")
	void failWhenScheduledExpirationOccursBeforeValidTo() {
		long eventId = generateUniqueId();
		long issueId = insertDummyIssue(eventId, "2024-01-01 10:00:00");
		jdbcTemplate.update("""
				UPDATE coupon_issue
				SET status = 'EXPIRED', valid_to = '2024-01-08 10:00:00'
				WHERE issue_id = :issueId
				""", new MapSqlParameterSource("issueId", issueId));
		insertDummyHistory(
				issueId, "ISSUED", "EXPIRED", "EXPIRED_BY_SCHEDULE", "2024-01-02 10:00:00");

		for (Scope scope : createTestScopes(eventId)) {
			CheckOutcome outcome = check.check(scope);
			assertThat(outcome.isPass()).as("Scope: %s", scope.getType()).isFalse();
			assertThat(outcome.getViolationCount()).as("Scope: %s", scope.getType()).isEqualTo(1);
		}
	}

	@Test
	@DisplayName("AS_OF_RANGE 스코프: created_at이 범위 밖인 경우 시간 위반이 있어도 검증 대상에서 제외된다")
	void passWhenIssueCreatedAtOutsideRange() {
		long eventId = generateUniqueId();
		java.time.LocalDateTime targetTime = java.time.LocalDateTime.of(2026, 8, 1, 12, 0, 0);
		// targetTime - 2시간 시점에 생성된 발급 건 (2초 차이로 위반 발생)
		long issueId = insertDummyIssue(eventId, "2026-08-01 10:00:00", targetTime.minusHours(2));
		insertDummyHistory(issueId, null, "ISSUED", "2026-08-01 10:00:02");

		// [targetTime, targetTime + 1시간) 구간 검증 시 제외되어 PASS
		Scope rangeScope = Scope.ofAsOfRange(targetTime, targetTime.plusHours(1));
		CheckOutcome outcome = check.check(rangeScope);
		assertThat(outcome.isPass()).isTrue();

		// [targetTime - 3시간, targetTime) 구간 검증 시 포함되어 FAIL
		Scope matchScope = Scope.ofAsOfRange(targetTime.minusHours(3), targetTime);
		CheckOutcome matchOutcome = check.check(matchScope);
		assertThat(matchOutcome.isPass()).isFalse();
		assertThat(matchOutcome.getViolationCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("AS_OF_RANGE 스코프: [from, to) 경계값 - created_at == from 은 포함되고 created_at == to 는 제외된다")
	void asOfRangeBoundaryConditions() {
		long eventId = generateUniqueId();
		java.time.LocalDateTime from = java.time.LocalDateTime.of(2026, 8, 1, 10, 0, 0);
		java.time.LocalDateTime to = java.time.LocalDateTime.of(2026, 8, 1, 12, 0, 0);

		// 1) 정확히 from 시점에 생성된 위반 발급 건 (2초 차이) -> 포함 (FAIL)
		long issueAtFrom = insertDummyIssue(eventId, "2026-08-01 10:00:00", from);
		insertDummyHistory(issueAtFrom, null, "ISSUED", "2026-08-01 10:00:02");

		// 2) 정확히 to 시점에 생성된 위반 발급 건 (2초 차이) -> 제외 (< to)
		long issueAtTo = insertDummyIssue(eventId, "2026-08-01 12:00:00", to);
		insertDummyHistory(issueAtTo, null, "ISSUED", "2026-08-01 12:00:02");

		Scope rangeScope = Scope.ofAsOfRange(from, to);
		CheckOutcome outcome = check.check(rangeScope);
		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
		assertThat(outcome.getViolations()).singleElement()
				.satisfies(v -> assertThat(v.getTargetId()).isEqualTo(issueAtFrom));
	}

	@Test
	@DisplayName("AS_OF_RANGE 스코프: 다중 발급 건 중 구간 내 정상 건과 위반 건이 혼재할 때 정확히 선별 검출한다")
	void asOfRangeMultiIssueFiltering() {
		long eventId = generateUniqueId();
		java.time.LocalDateTime from = java.time.LocalDateTime.of(2026, 8, 1, 10, 0, 0);
		java.time.LocalDateTime to = java.time.LocalDateTime.of(2026, 8, 1, 12, 0, 0);

		// 1) 구간 내 정상 발급 건 (오차 0초)
		long validIn = insertDummyIssue(eventId, "2026-08-01 10:30:00", from.plusMinutes(30));
		insertDummyHistory(validIn, null, "ISSUED", "2026-08-01 10:30:00");

		// 2) 구간 내 위반 발급 건 (오차 2초)
		long invalidIn = insertDummyIssue(eventId, "2026-08-01 10:45:00", from.plusMinutes(45));
		insertDummyHistory(invalidIn, null, "ISSUED", "2026-08-01 10:45:02");

		// 3) 구간 밖(이전) 위반 발급 건
		long invalidBefore = insertDummyIssue(eventId, "2026-08-01 09:00:00", from.minusHours(1));
		insertDummyHistory(invalidBefore, null, "ISSUED", "2026-08-01 09:00:05");

		// 4) 구간 밖(이후) 위반 발급 건
		long invalidAfter = insertDummyIssue(eventId, "2026-08-01 13:00:00", to.plusHours(1));
		insertDummyHistory(invalidAfter, null, "ISSUED", "2026-08-01 13:00:05");

		Scope rangeScope = Scope.ofAsOfRange(from, to);
		CheckOutcome outcome = check.check(rangeScope);
		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
		assertThat(outcome.getViolations()).singleElement()
				.satisfies(v -> assertThat(v.getTargetId()).isEqualTo(invalidIn));
	}

	private long insertDummyIssue(long eventId, String updatedAt) {
		return insertDummyIssue(eventId, updatedAt, java.time.LocalDateTime.now());
	}

	private long insertDummyIssue(long eventId, String updatedAt, java.time.LocalDateTime createdAt) {
		String sql = """
                INSERT INTO coupon_issue (event_id, user_id, issue_sequence, request_id, status, issued_at, valid_from, valid_to, created_at)
                VALUES (:eventId, :userId, :issueSequence, :requestId, 'ISSUED', :updatedAt, NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY), :createdAt)
                """;
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("eventId", eventId)
				.addValue("userId", generateUniqueId())
				.addValue("issueSequence", generateUniqueId())
				.addValue("requestId", "req-" + generateUniqueId())
				.addValue("updatedAt", updatedAt)
				.addValue("createdAt", createdAt);
		
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcTemplate.update(sql, params, keyHolder, new String[]{"issue_id"});
		return keyHolder.getKey().longValue();
	}

	private void insertDummyHistory(long issueId, String fromStatus, String toStatus, String occurredAt) {
		insertDummyHistory(issueId, fromStatus, toStatus, null, occurredAt);
	}

	private void insertDummyHistory(
			long issueId, String fromStatus, String toStatus, String reason, String occurredAt) {
		String sql = """
				INSERT INTO coupon_history (
					issue_id, from_status, to_status, reason, occurred_at, recorded_at
				)
				VALUES (:issueId, :fromStatus, :toStatus, :reason, :occurredAt, NOW())
                """;
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("issueId", issueId)
				.addValue("fromStatus", fromStatus)
				.addValue("toStatus", toStatus)
				.addValue("reason", reason)
				.addValue("occurredAt", occurredAt);
		jdbcTemplate.update(sql, params);
	}
}
