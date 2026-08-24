package com.ace.consistency.check;

import com.ace.consistency.common.ConsistencyCheck.CheckOutcome;
import com.ace.consistency.common.Scope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StateMachineConsistencyCheckTest extends ConsistencyCheckIntegrationTestBase {

	private StateMachineConsistencyCheck check;

	@BeforeEach
	void setUp() {
		check = new StateMachineConsistencyCheck(jdbcTemplate);
	}

	private List<Scope> createTestScopes(long eventId) {
		return List.of(
				Scope.ofEvent(eventId),
				Scope.all(List.of(eventId), LocalDateTime.now())
		);
	}

	@Test
	@DisplayName("정상적인 이력 체인이면 PASS 반환 (발급 -> 사용 -> 발급 -> 사용)")
	void passWhenStatusChainIsValid() {
		long eventId = generateUniqueId();
		long issueId = insertDummyIssue(eventId);
		insertDummyHistory(issueId, null, "ISSUED"); // 최초 발급
		insertDummyHistory(issueId, "ISSUED", "USED");
		insertDummyHistory(issueId, "USED", "ISSUED"); // 취소 역할(발급으로 돌아감)
		insertDummyHistory(issueId, "ISSUED", "USED"); // 다시 사용

		for (Scope scope : createTestScopes(eventId)) {
			CheckOutcome outcome = check.check(scope);
			if (outcome.isPass()) {
				System.out.println("DEBUG PASS in failWhenLostUpdateHappens: expected fail");
			} else {
				System.out.println("DEBUG FAIL (EXPECTED) in failWhenLostUpdateHappens: " + outcome.getDiffDetail());
			}
			assertThat(outcome.isPass()).as("Scope: %s", scope.getType()).isTrue();
		}
	}

	@Test
	@DisplayName("따닥 클릭 - 잃어버린 업데이트 발생 시 FAIL 반환 (연속성 붕괴)")
	void failWhenLostUpdateHappens() {
		long eventId = generateUniqueId();
		long issueId = insertDummyIssue(eventId);
		insertDummyHistory(issueId, null, "ISSUED"); // 최초 발급
		insertDummyHistory(issueId, "ISSUED", "USED");
		insertDummyHistory(issueId, "ISSUED", "USED"); // 락 뚫림, 이전 상태(USED)와 현재 출발(ISSUED) 불일치

		for (Scope scope : createTestScopes(eventId)) {
			CheckOutcome outcome = check.check(scope);
			assertThat(outcome.isPass()).as("Scope: %s", scope.getType()).isFalse();
			assertThat(outcome.getViolationCount()).as("Scope: %s", scope.getType()).isEqualTo(1);
		}
	}

	@Test
	@DisplayName("비정상 상태 전이 발생 시 FAIL 반환 (USED -> EXPIRED 비즈니스 룰 위반)")
	void failWhenInvalidStateTransitionHappens() {
		long eventId = generateUniqueId();
		long issueId = insertDummyIssue(eventId);
		insertDummyHistory(issueId, null, "ISSUED"); // 최초 발급
		insertDummyHistory(issueId, "ISSUED", "USED");
		insertDummyHistory(issueId, "USED", "EXPIRED"); // 연속성은 맞지만 허용되지 않은 전이!

		for (Scope scope : createTestScopes(eventId)) {
			CheckOutcome outcome = check.check(scope);
			assertThat(outcome.isPass()).as("Scope: %s", scope.getType()).isFalse();
			assertThat(outcome.getViolationCount()).as("Scope: %s", scope.getType()).isEqualTo(1);
		}
	}

	@Test
	@DisplayName("낡은 데이터를 읽은 배치 로직의 덮어쓰기 발생 시 FAIL 반환")
	void failWhenStaleDataOverwrittenByBatch() {
		long eventId = generateUniqueId();
		long issueId = insertDummyIssue(eventId);
		insertDummyHistory(issueId, null, "ISSUED"); // 최초 발급
		insertDummyHistory(issueId, "ISSUED", "USED"); // 낮 12시 유저 사용
		insertDummyHistory(issueId, "ISSUED", "EXPIRED"); // 자정 배치 잘못된 덮어쓰기 (연속성 붕괴 및 무효전이)

		for (Scope scope : createTestScopes(eventId)) {
			CheckOutcome outcome = check.check(scope);
			assertThat(outcome.isPass()).as("Scope: %s", scope.getType()).isFalse();
			assertThat(outcome.getViolationCount()).as("Scope: %s", scope.getType()).isEqualTo(1);
		}
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
