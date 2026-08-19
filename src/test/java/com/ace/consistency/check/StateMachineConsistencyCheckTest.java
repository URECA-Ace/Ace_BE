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

class StateMachineConsistencyCheckTest extends CheckIntegrationTestBase {

	private StateMachineConsistencyCheck check;

	@BeforeEach
	void setUp() {
		check = new StateMachineConsistencyCheck(jdbcTemplate);
	}

	@Test
	@DisplayName("정상적인 이력 체인이면 PASS 반환")
	void passWhenStatusChainIsValid() {
		long eventId = generateUniqueId();
		long issueId = insertDummyIssue(eventId);
		insertDummyHistory(issueId, "ISSUED", "USED");
		insertDummyHistory(issueId, "USED", "CANCELED");
		insertDummyHistory(issueId, "CANCELED", "USED"); // 취소 후 재사용 정상 케이스

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	@DisplayName("따닥 클릭 - 잃어버린 업데이트 발생 시 FAIL 반환")
	void failWhenLostUpdateHappens() {
		long eventId = generateUniqueId();
		long issueId = insertDummyIssue(eventId);
		insertDummyHistory(issueId, "ISSUED", "USED");
		insertDummyHistory(issueId, "ISSUED", "USED"); // 락 뚫림

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("이기종 상태 동시성 충돌 발생 시 FAIL 반환")
	void failWhenHeterogeneousRaceConditionHappens() {
		long eventId = generateUniqueId();
		long issueId = insertDummyIssue(eventId);
		insertDummyHistory(issueId, "ISSUED", "USED");
		insertDummyHistory(issueId, "ISSUED", "CANCELED"); // 락 뚫림 (이기종 충돌)

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("멱등성 처리 실패 (네트워크 타임아웃 재시도) 발생 시 FAIL 반환")
	void failWhenIdempotencyFails() {
		long eventId = generateUniqueId();
		long issueId = insertDummyIssue(eventId);
		insertDummyHistory(issueId, "ISSUED", "USED");
		insertDummyHistory(issueId, "USED", "CANCELED");
		insertDummyHistory(issueId, "USED", "CANCELED"); // 멱등성 방어 뚫림

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("낡은 데이터를 읽은 배치 로직의 덮어쓰기 발생 시 FAIL 반환")
	void failWhenStaleDataOverwrittenByBatch() {
		long eventId = generateUniqueId();
		long issueId = insertDummyIssue(eventId);
		insertDummyHistory(issueId, "ISSUED", "USED"); // 낮 12시 유저 사용
		insertDummyHistory(issueId, "ISSUED", "EXPIRED"); // 자정 배치 잘못된 덮어쓰기

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
