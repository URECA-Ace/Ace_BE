package com.ace.consistency.check;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import com.ace.consistency.common.ConsistencyCheck.CheckOutcome;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.ViolationTargetType;

// 재고 정합성 검사의 Drain 조건을 확인
class StockConsistencyCheckTest extends ConsistencyCheckIntegrationTestBase {

	private final List<Long> createdEventIds = new ArrayList<>();

	private StockConsistencyCheck check;

	@BeforeEach
	void setUp() {
		check = new StockConsistencyCheck(jdbcTemplate);
	}

	// 베이스 클래스는 coupon_issue / coupon_history 만 지운다.
	// 이 테스트가 만든 회차는 직접 정리한다
	@AfterEach
	void deleteCreatedEvents() {
		if (createdEventIds.isEmpty()) {
			return;
		}
		jdbcTemplate.update(
				"DELETE FROM coupon_event WHERE event_id IN (:eventIds)",
				new MapSqlParameterSource("eventIds", createdEventIds));
		createdEventIds.clear();
	}

	@Test
	@DisplayName("마감된 회차의 집계가 실제 발급 건수와 맞으면 통과한다")
	void passesWhenClosedEventAggregateMatches() {
		long eventId = generateUniqueId();
		insertEvent(eventId, "CLOSED", 1_000, 400, 600);
		insertIssues(eventId, 400);

		assertThat(check.check(allScope(eventId)).isPass()).isTrue();
		assertThat(check.check(Scope.ofEvent(eventId)).isPass()).isTrue();
	}

	@Test
	@DisplayName("마감된 회차의 집계가 어긋나면 잡아낸다")
	void detectsMismatchOnClosedEvent() {
		long eventId = generateUniqueId();
		// 집계는 400 이라 적혀 있는데 실제 발급은 401 건이다
		insertEvent(eventId, "CLOSED", 1_000, 400, 600);
		insertIssues(eventId, 401);

		CheckOutcome allOutcome = check.check(allScope(eventId));
		assertThat(allOutcome.isPass()).isFalse();
		assertThat(allOutcome.getViolations()).singleElement().satisfies(violation -> {
			assertThat(violation.getTargetType()).isEqualTo(ViolationTargetType.EVENT);
			assertThat(violation.getTargetId()).isEqualTo(eventId);
		});
		assertThat(check.check(Scope.ofEvent(eventId)).isPass()).isFalse();
	}

	@Test
	@DisplayName("발급이 진행 중인 회차는 ALL 스코프 검사 대상에서 빠진다")
	void skipsIssuingEventInAllScope() {
		long eventId = generateUniqueId();
		// 집계 스냅샷이 아직 반영되기 전의 상태
		// 실제 발급은 400 건인데 집계는 0 이다
		insertEvent(eventId, "OPEN", 1_000, 0, 1_000);
		insertIssues(eventId, 400);

		CheckOutcome outcome = check.check(allScope(eventId));

		assertThat(outcome.isPass())
				.as("진행 중인 회차의 집계 랙을 위반으로 잡으면 정오 부하마다 위반이 쌓인다")
				.isTrue();
	}

	@Test
	@DisplayName("EVENT 스코프는 진행 중인 회차도 그대로 검사한다")
	void stillChecksIssuingEventInEventScope() {
		long eventId = generateUniqueId();
		insertEvent(eventId, "OPEN", 1_000, 0, 1_000);
		insertIssues(eventId, 400);

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass())
				.as("호출자가 대상을 지정한 검사까지 막으면 안 된다")
				.isFalse();
	}

	@Test
	@DisplayName("소진 처리된 회차도 ALL 스코프 검사 대상이다")
	void checksSoldOutEventInAllScope() {
		long eventId = generateUniqueId();
		// 소진 처리는 파이프라인이 빈 뒤에 찍히므로 이 시점 집계는 확정된 값이어야 한다
		insertEvent(eventId, "SOLD_OUT", 1_000, 999, 0);
		insertIssues(eventId, 1_000);

		assertThat(check.check(allScope(eventId)).isPass()).isFalse();
	}

	@Test
	@DisplayName("마감 직후 safety margin 안에 발급이 있어도 오탐하지 않는다")
	void doesNotFalselyReportWhenLastIssueIsInsideSafetyMargin() {
		// 집계는 마감 시점의 최신 확정 수인데 COUNT 에만 컷오프를 걸면 직전 구간의 발급이 빠져 불일치로 보인다
		long eventId = generateUniqueId();
		insertEvent(eventId, "CLOSED", 1_000, 400, 600);
		insertIssues(eventId, 400);

		// ALL 스코프 스케쥴러가 쓰는 to = now - safetyMargin 을 그대로 재현
		Scope withSafetyMargin = Scope.all(
				List.of(eventId), LocalDateTime.now().minusSeconds(10));

		assertThat(check.check(withSafetyMargin).isPass())
				.as("마감 이후에는 새로 저장되는 건이 없으므로 전체 건수와 비교해야 한다")
				.isTrue();
	}

	private Scope allScope(long eventId) {
		return Scope.all(List.of(eventId), LocalDateTime.now().plusDays(1));
	}

	private void insertEvent(
			long eventId, String status, int totalStock, int issuedQuantity, int remainingStock) {
		jdbcTemplate.update("""
				INSERT INTO coupon_event
					(event_id, coupon_id, round, open_at, close_at, total_stock, remaining_stock,
					 issued_quantity, per_user_limit, status, created_at, updated_at)
				VALUES
					(:eventId, 1, :round, NOW() - INTERVAL 2 HOUR, NOW() - INTERVAL 1 HOUR,
					 :totalStock, :remainingStock, :issuedQuantity, 1, :status, NOW(), NOW())
				""",
				new MapSqlParameterSource()
						.addValue("eventId", eventId)
						.addValue("round", eventId)
						.addValue("totalStock", totalStock)
						.addValue("remainingStock", remainingStock)
						.addValue("issuedQuantity", issuedQuantity)
						.addValue("status", status));
		createdEventIds.add(eventId);
	}

	private void insertIssues(long eventId, int count) {
		for (int i = 1; i <= count; i++) {
			jdbcTemplate.update("""
					INSERT INTO coupon_issue
						(event_id, user_id, issue_sequence, request_id, status,
						 issued_at, valid_from, valid_to, created_at)
					VALUES
						(:eventId, :userId, :sequence, :requestId, 'ISSUED',
						 NOW(), NOW(), NOW() + INTERVAL 7 DAY, NOW())
					""",
					new MapSqlParameterSource()
							.addValue("eventId", eventId)
							.addValue("userId", eventId + i)
							.addValue("sequence", i)
							.addValue("requestId", java.util.UUID.randomUUID().toString()));
		}
	}
}
