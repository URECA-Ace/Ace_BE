package com.ace;

import com.ace.consistency.check.ConsistencyCheckIntegrationTestBase;
import com.ace.consistency.check.StateMachineConsistencyCheck;
import com.ace.consistency.check.StockConsistencyCheck;
import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.ConsistencyVerificationRunner;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.coupon.service.CouponIssueService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConsistencyVerificationRunner가 존재하지 않는 event_id(EVENT 스코프)로 호출됐을 때
 * EventNotFoundException을 실제로 던지는지 확인하는 통합 테스트.
 *
 * StockConsistencyCheckTest와 달리 여기서는 반드시 Runner를 거쳐서 호출해야 한다 —
 * 존재 여부 검증은 Check가 아니라 Runner가 담당하기 때문이다.
 *
 * Runner는 CouponEventRepository(Spring Data JPA)에 의존하는데, 이건 @JdbcTest 슬라이스에서는
 * 로드되지 않는 컴포넌트라서 @SpringBootTest로 전체 컨텍스트를 띄운다
 * (그만큼 StockConsistencyCheckTest보다 느리다).
 *
 * ConsistencyCheckIntegrationTestBase가 띄우는 Testcontainers MySQL에 대고 실행한다.
 */
class ConsistencyVerificationRunnerEventNotFoundTest extends ConsistencyCheckIntegrationTestBase {

	// Redis + Lua 기반 실제 구현체가 추가되기 전까지 전체 Context에서만 대체한다.
	@MockitoBean
	private CouponIssueService couponIssueService;

	@Autowired
	private ConsistencyVerificationRunner runner;

	@Autowired
	private StockConsistencyCheck stockConsistencyCheck;

	@Autowired
	private StateMachineConsistencyCheck stateMachineConsistencyCheck;

	private Long createdEventId;

	@AfterEach
	void cleanUpEvent() {
		if (createdEventId != null) {
			jdbcTemplate.update("DELETE FROM coupon_event WHERE event_id = :eventId",
					new MapSqlParameterSource("eventId", createdEventId));
			createdEventId = null;
		}
	}

	/**
	 * [검증 목적] 존재하지 않는 event_id로 Runner.run()을 호출하면 EventNotFoundException이
	 * 실제로 던져지는지, 그리고 그 예외 메시지에 문제의 event_id가 포함되는지 확인한다.
	 */
	@Test
	void 존재하지_않는_이벤트로_Runner를_호출하면_EventNotFoundException이_발생한다() {
		Long nonExistentEventId = findNonExistentEventId();
		List<ConsistencyCheck> checks = List.of(stockConsistencyCheck, stateMachineConsistencyCheck);

		EntityNotFoundException ex = assertThrows(EntityNotFoundException.class,
				() -> runner.run(checks, Scope.ofEvent(nonExistentEventId), TriggerType.ON_DEMAND));

		assertTrue(ex.getMessage().contains(String.valueOf(nonExistentEventId)),
				"예외 메시지에 문제의 event_id가 포함되어야 합니다: " + ex.getMessage());
	}

	/**
	 * [검증 목적] 존재하지 않는 이벤트로 인해 예외가 던져지면, Check가 애초에 하나도 실행되지
	 * 않고 verification_result 테이블에도 아무 행이 추가되지 않는지 확인한다
	 * (Runner가 Check 실행 전에 존재 여부를 먼저 확인하므로, 불필요한 실행/저장이 없어야 한다).
	 */
	@Test
	void 존재하지_않는_이벤트면_결과가_저장되지_않는다() {
		Long nonExistentEventId = findNonExistentEventId();
		List<ConsistencyCheck> checks = List.of(stockConsistencyCheck, stateMachineConsistencyCheck);

		long before = countVerificationResults();

		assertThrows(EntityNotFoundException.class,
				() -> runner.run(checks, Scope.ofEvent(nonExistentEventId), TriggerType.ON_DEMAND));

		long after = countVerificationResults();

		assertEquals(before, after, "존재하지 않는 이벤트 검증 시도로 인해 결과가 저장되면 안 됩니다.");
	}

	/**
	 * [검증 목적] (대조군) 실제로 존재하는 event_id면 Runner가 정상적으로 결과를 반환하고
	 * 예외를 던지지 않는지 확인한다.
	 */
	@Test
	void 존재하는_이벤트면_정상적으로_결과를_반환한다() {
		Long existingEventId = insertDummyEvent();
		List<ConsistencyCheck> checks = List.of(stockConsistencyCheck, stateMachineConsistencyCheck);

		assertDoesNotThrow(() -> {
			var results = runner.run(checks, Scope.ofEvent(existingEventId), TriggerType.ON_DEMAND);
			assertEquals(1, results.size());
		});
	}

	// ----------------- 헬퍼 -----------------

	private Long findNonExistentEventId() {
		return jdbcTemplate.queryForObject(
				"SELECT COALESCE(MAX(event_id), 0) + 999999999 FROM coupon_event",
				Map.of(), Long.class);
	}

	private Long insertDummyEvent() {
		long eventId = generateUniqueId();
		String sql = """
                INSERT INTO coupon_event (event_id, coupon_id, round, open_at, close_at, total_stock, remaining_stock, issued_quantity, per_user_limit, status, created_at, updated_at)
                VALUES (:eventId, :eventId, 1, NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY), 100, 100, 0, 1, 'OPEN', NOW(), NOW())
                """;
		jdbcTemplate.update(sql, new MapSqlParameterSource("eventId", eventId));
		createdEventId = eventId;
		return eventId;
	}

	private long countVerificationResults() {
		Long count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM verification_result", Map.of(), Long.class);
		return count == null ? 0 : count;
	}
}
