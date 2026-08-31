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
				Scope.ofAsOfRange(LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1)),
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
			assertThat(outcome.getViolations()).singleElement()
					.satisfies(violation -> assertThat(violation.getTargetType()).isEqualTo(ViolationTargetType.ISSUE));
		}
	}

	// "USED -> EXPIRED"처럼 연속성은 맞지만 허용되지 않은 전이 자체를 잡는 책임은
	// CouponHistoryStructuralConsistencyCheck로 옮겨졌다(issue #36 — 중복 검증 로직 제거).
	// 이 클래스는 이제 이력 체인의 연속성(from_status <=> 직전 이력의 to_status)만 검증한다.

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

	@Test
	@DisplayName("AS_OF_RANGE 스코프: created_at이 범위 밖인 경우 위반이 있어도 검증 대상에서 제외된다")
	void passWhenIssueCreatedAtOutsideRange() {
		long eventId = generateUniqueId();
		LocalDateTime targetTime = LocalDateTime.of(2026, 8, 1, 12, 0, 0);
		// targetTime - 2시간 시점에 생성된 발급 건 (위반 발생)
		long issueId = insertDummyIssue(eventId, targetTime.minusHours(2));
		insertDummyHistory(issueId, null, "ISSUED");
		insertDummyHistory(issueId, "ISSUED", "USED");
		insertDummyHistory(issueId, "ISSUED", "USED"); // 잃어버린 업데이트 위반

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
		LocalDateTime from = LocalDateTime.of(2026, 8, 1, 10, 0, 0);
		LocalDateTime to = LocalDateTime.of(2026, 8, 1, 12, 0, 0);

		// 1) 정확히 from 시점에 생성된 위반 발급 건 -> 포함되어야 함 (FAIL)
		long issueAtFrom = insertDummyIssue(eventId, from);
		insertDummyHistory(issueAtFrom, null, "ISSUED");
		insertDummyHistory(issueAtFrom, "ISSUED", "USED");
		insertDummyHistory(issueAtFrom, "ISSUED", "USED");

		// 2) 정확히 to 시점에 생성된 위반 발급 건 -> 제외되어야 함 (< to)
		long issueAtTo = insertDummyIssue(eventId, to);
		insertDummyHistory(issueAtTo, null, "ISSUED");
		insertDummyHistory(issueAtTo, "ISSUED", "USED");
		insertDummyHistory(issueAtTo, "ISSUED", "USED");

		Scope rangeScope = Scope.ofAsOfRange(from, to);
		CheckOutcome outcome = check.check(rangeScope);
		assertThat(outcome.isPass()).isFalse();
		// issueAtFrom 1건만 검출되어야 함
		assertThat(outcome.getViolationCount()).isEqualTo(1);
		assertThat(outcome.getViolations()).singleElement()
				.satisfies(v -> assertThat(v.getTargetId()).isEqualTo(issueAtFrom));
	}

	@Test
	@DisplayName("AS_OF_RANGE 스코프: 다중 발급 건 중 구간 내 정상 건과 위반 건이 혼재할 때 정확히 선별 검출한다")
	void asOfRangeMultiIssueFiltering() {
		long eventId = generateUniqueId();
		LocalDateTime from = LocalDateTime.of(2026, 8, 1, 10, 0, 0);
		LocalDateTime to = LocalDateTime.of(2026, 8, 1, 12, 0, 0);

		// 1) 구간 내 정상 발급 건
		long validIn = insertDummyIssue(eventId, from.plusMinutes(30));
		insertDummyHistory(validIn, null, "ISSUED");
		insertDummyHistory(validIn, "ISSUED", "USED");

		// 2) 구간 내 위반 발급 건
		long invalidIn = insertDummyIssue(eventId, from.plusMinutes(45));
		insertDummyHistory(invalidIn, null, "ISSUED");
		insertDummyHistory(invalidIn, "ISSUED", "USED");
		insertDummyHistory(invalidIn, "ISSUED", "USED");

		// 3) 구간 밖(이전) 위반 발급 건
		long invalidBefore = insertDummyIssue(eventId, from.minusHours(1));
		insertDummyHistory(invalidBefore, null, "ISSUED");
		insertDummyHistory(invalidBefore, "ISSUED", "EXPIRED");

		// 4) 구간 밖(이후) 위반 발급 건
		long invalidAfter = insertDummyIssue(eventId, to.plusHours(1));
		insertDummyHistory(invalidAfter, null, "ISSUED");
		insertDummyHistory(invalidAfter, "ISSUED", "EXPIRED");

		Scope rangeScope = Scope.ofAsOfRange(from, to);
		CheckOutcome outcome = check.check(rangeScope);
		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
		assertThat(outcome.getViolations()).singleElement()
				.satisfies(v -> assertThat(v.getTargetId()).isEqualTo(invalidIn));
	}

	private long insertDummyIssue(long eventId) {
		return insertDummyIssue(eventId, LocalDateTime.now());
	}

	private long insertDummyIssue(long eventId, LocalDateTime createdAt) {
		String sql = """
                INSERT INTO coupon_issue (event_id, user_id, issue_sequence, request_id, status, issued_at, valid_from, valid_to, created_at)
                VALUES (:eventId, :userId, :issueSequence, :requestId, 'ISSUED', NOW(), NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY), :createdAt)
                """;
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("eventId", eventId)
				.addValue("userId", generateUniqueId())
				.addValue("issueSequence", generateUniqueId())
				.addValue("requestId", "req-" + generateUniqueId())
				.addValue("createdAt", createdAt);
		
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
