package com.ace;

import com.ace.consistency.check.DuplicateConsistencyCheck;
import com.ace.consistency.check.StockConsistencyCheck;
import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.Scope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StockConsistencyCheck / DuplicateConsistencyCheck를 실제 로컬 MySQL(더미데이터 적재된 DB)에
 * 대고 검증하는 통합 테스트.
 *
 * @JdbcTest: JdbcTemplate/NamedParameterJdbcTemplate 등 JDBC 관련 빈만 가볍게 로드한다
 *            (전체 Spring 컨텍스트를 안 띄워서 빠르다).
 * @AutoConfigureTestDatabase(replace = NONE): @JdbcTest의 기본 동작(내장 임베디드 DB로 치환)을 끄고,
 *            application.yml에 설정된 실제 로컬 MySQL 커넥션을 그대로 사용한다.
 * 각 테스트 메서드는 기본적으로 트랜잭션으로 감싸져서 종료 시 자동 롤백되므로,
 * 위반 데이터를 일부러 주입하는 테스트를 실제 DB에서 돌려도 데이터가 실제로 오염되지 않는다.
 *
 * 각 테스트 실행 시간과 결과는 RESULTS에 모아두고, 전체 테스트가 끝나면(@AfterAll)
 * 한 번에 표로 정리해서 콘솔에 출력한다.
 * (표에 찍히는 라벨은 전부 영문/숫자로만 구성해서 콘솔 폭 계산 문제를 피한다 — 한글/이모지는
 *  터미널마다 렌더링 폭이 달라 String.format의 %-Ns 패딩과 어긋날 수 있다.)
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class StockAndDuplicateConsistencyCheckTest {

	private static final List<ResultRow> RESULTS = new ArrayList<>();

	@Autowired
	private NamedParameterJdbcTemplate jdbcTemplate;

	private StockConsistencyCheck stockCheck;
	private DuplicateConsistencyCheck duplicateCheck;

	@BeforeEach
	void setUp() {
		stockCheck = new StockConsistencyCheck(jdbcTemplate);
		duplicateCheck = new DuplicateConsistencyCheck(jdbcTemplate);
	}

	/**
	 * [검증 목적] 재고 정합성 Check를 ALL 스코프(전체 이벤트 대상)로 실행했을 때
	 * 실제 더미데이터 전체를 대상으로 정상적으로 동작하는지 확인한다.
	 * (CANCELED 반영 복구 작업을 마친 상태라면 PASS가 나와야 정상이다.)
	 */
	@Test
	void 재고_정합성_ALL_스코프_실제_데이터로_확인() {
		ConsistencyCheck.CheckOutcome outcome = runTimed(
				"Stock_ALL", "StockConsistencyCheck", "ALL", () -> stockCheck.check(Scope.all()));

		assertTrue(outcome.isPass(), "전체 재고 정합성이 깨져 있습니다: " + outcome.getDiffDetail());
	}

	/**
	 * [검증 목적] 재고 정합성 Check를 EVENT 스코프(특정 event_id 하나)로 실행했을 때도
	 * 정상적으로 동작하는지 확인한다. 실제 존재하는 event_id를 DB에서 하나 뽑아서 사용한다.
	 */
	@Test
	void 재고_정합성_EVENT_스코프_실제_데이터로_확인() {
		Long eventId = findAnyEventId();

		ConsistencyCheck.CheckOutcome outcome = runTimed(
				"Stock_EVENT(" + eventId + ")", "StockConsistencyCheck", "EVENT",
				() -> stockCheck.check(Scope.ofEvent(eventId)));

		assertTrue(outcome.isPass(), "event_id=" + eventId + " 재고 정합성이 깨져 있습니다: " + outcome.getDiffDetail());
	}

	/**
	 * [검증 목적] 재고 데이터를 일부러 어긋나게 만든 뒤(remaining_stock을 +1 조작),
	 * StockConsistencyCheck가 이를 정확히 FAIL로 잡아내는지 확인한다.
	 * 이 테스트는 트랜잭션으로 실행되어 종료 시 자동 롤백되므로 실제 데이터는 변경되지 않는다.
	 */
	@Test
	void 재고_불일치를_고의로_만들면_FAIL로_감지된다() {
		Long eventId = findAnyEventId();

		jdbcTemplate.update(
				"UPDATE coupon_event SET remaining_stock = remaining_stock + 1 WHERE event_id = :eventId",
				new MapSqlParameterSource("eventId", eventId));

		ConsistencyCheck.CheckOutcome outcome = runTimed(
				"Stock_Violation(" + eventId + ")", "StockConsistencyCheck", "EVENT",
				() -> stockCheck.check(Scope.ofEvent(eventId)));

		assertFalse(outcome.isPass(), "고의로 재고를 어긋나게 했는데도 PASS로 나왔습니다 — 쿼리 로직을 확인하세요.");
		assertEquals(1, outcome.getViolationCount());
	}

	/**
	 * [검증 목적] 1인 1매(중복) 정합성 Check를 ALL 스코프로 실행했을 때
	 * 실제 더미데이터 전체를 대상으로 위반이 없는지(정상 상태인지) 확인한다.
	 */
	@Test
	void 중복_정합성_ALL_스코프_실제_데이터로_확인() {
		ConsistencyCheck.CheckOutcome outcome = runTimed(
				"Duplicate_ALL", "DuplicateConsistencyCheck", "ALL", () -> duplicateCheck.check(Scope.all()));

		assertTrue(outcome.isPass(), "전체 1인1매 정합성이 깨져 있습니다: " + outcome.getDiffDetail());
	}

	/**
	 * [검증 목적] 1인 1매 Check를 EVENT 스코프로 실행했을 때도 정상 동작하는지 확인한다.
	 */
	@Test
	void 중복_정합성_EVENT_스코프_실제_데이터로_확인() {
		Long eventId = findAnyEventId();

		ConsistencyCheck.CheckOutcome outcome = runTimed(
				"Duplicate_EVENT(" + eventId + ")", "DuplicateConsistencyCheck", "EVENT",
				() -> duplicateCheck.check(Scope.ofEvent(eventId)));

		assertTrue(outcome.isPass(), "event_id=" + eventId + " 1인1매 정합성이 깨져 있습니다: " + outcome.getDiffDetail());
	}

	/**
	 * [검증 목적] 이미 발급 이력이 있는 이벤트의 per_user_limit을 0으로 고의로 낮춰서,
	 * 기존 발급 건들이 전부 "제한 초과"가 되도록 만든 뒤 DuplicateConsistencyCheck가
	 * 이를 정확히 FAIL로 잡아내는지 확인한다. 트랜잭션 롤백으로 실제 데이터는 보존된다.
	 */
	@Test
	void 인당_제한을_고의로_낮추면_FAIL로_감지된다() {
		Long eventId = findEventIdWithAtLeastOneActiveIssue();

		jdbcTemplate.update(
				"UPDATE coupon_event SET per_user_limit = 0 WHERE event_id = :eventId",
				new MapSqlParameterSource("eventId", eventId));

		ConsistencyCheck.CheckOutcome outcome = runTimed(
				"Duplicate_Violation(" + eventId + ")", "DuplicateConsistencyCheck", "EVENT",
				() -> duplicateCheck.check(Scope.ofEvent(eventId)));

		assertFalse(outcome.isPass(), "per_user_limit을 0으로 낮췄는데도 PASS로 나왔습니다 — 쿼리 로직을 확인하세요.");
		assertTrue(outcome.getViolationCount() > 0);
	}

	/**
	 * 전체 테스트가 끝난 뒤 RESULTS에 모아둔 실행 결과(시간 포함)를 표로 출력한다.
	 */
	@AfterAll
	static void printSummaryTable() {
		String format = "| %-22s | %-26s | %-6s | %-7s | %-8s | %10s%n";
		String divider = "-".repeat(100);

		System.out.println();
		System.out.println(divider);
		System.out.printf(format, "TEST CASE", "CHECK", "SCOPE", "STATUS", "VIOL#", "TIME(ms)");
		System.out.println(divider);
		for (ResultRow r : RESULTS) {
			System.out.printf(format, r.testCase(), r.checkName(), r.scope(),
					r.pass() ? "PASS" : "FAIL", r.violationCount(), r.durationMillis());
		}
		System.out.println(divider);

		long totalMs = RESULTS.stream().mapToLong(ResultRow::durationMillis).sum();
		long passCount = RESULTS.stream().filter(ResultRow::pass).count();
		System.out.printf("Total: %d run / PASS %d / FAIL %d / elapsed %dms%n%n",
				RESULTS.size(), passCount, RESULTS.size() - passCount, totalMs);
	}

	// ----------------- 헬퍼: 실행 시간을 재면서 Check를 실행하고 결과를 RESULTS에 적재 -----------------

	private ConsistencyCheck.CheckOutcome runTimed(String testCase, String checkName, String scope,
												   java.util.function.Supplier<ConsistencyCheck.CheckOutcome> action) {
		long start = System.currentTimeMillis();
		ConsistencyCheck.CheckOutcome outcome = action.get();
		long duration = System.currentTimeMillis() - start;

		RESULTS.add(new ResultRow(testCase, checkName, scope, outcome.isPass(),
				outcome.getViolationCount(), duration));
		return outcome;
	}

	private record ResultRow(String testCase, String checkName, String scope,
							 boolean pass, int violationCount, long durationMillis) {}

	// ----------------- 헬퍼: 테스트에 쓸 실제 event_id를 DB에서 조회 -----------------

	private Long findAnyEventId() {
		return jdbcTemplate.queryForObject(
				"SELECT event_id FROM coupon_event ORDER BY event_id LIMIT 1",
				Map.of(), Long.class);
	}

	private Long findEventIdWithAtLeastOneActiveIssue() {
		return jdbcTemplate.queryForObject("""
                SELECT ci.event_id
                FROM coupon_issue ci
                WHERE ci.status IN ('ISSUED','USED','EXPIRED')
                GROUP BY ci.event_id
                LIMIT 1
                """, Map.of(), Long.class);
	}
}