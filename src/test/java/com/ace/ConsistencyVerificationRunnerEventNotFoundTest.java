package com.ace;

import com.ace.consistency.check.DuplicateConsistencyCheck;
import com.ace.consistency.check.StockConsistencyCheck;
import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.ConsistencyVerificationRunner;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.coupon.service.CouponIssueService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConsistencyVerificationRunner가 존재하지 않는 event_id(EVENT 스코프)로 호출됐을 때
 * EventNotFoundException을 실제로 던지는지 확인하는 통합 테스트.
 *
 * StockAndDuplicateConsistencyCheckTest와 달리 여기서는 반드시 Runner를 거쳐서 호출해야 한다 —
 * 존재 여부 검증은 Check가 아니라 Runner가 담당하기 때문이다.
 *
 * Runner는 CouponEventRepository(Spring Data JPA)에 의존하는데, 이건 @JdbcTest 슬라이스에서는
 * 로드되지 않는 컴포넌트라서 @SpringBootTest로 전체 컨텍스트를 띄운다
 * (그만큼 StockAndDuplicateConsistencyCheckTest보다 느리다).
 */
@SpringBootTest
class ConsistencyVerificationRunnerEventNotFoundTest {

	// Redis + Lua 기반 실제 구현체가 추가되기 전까지 전체 Context에서만 대체한다.
	@MockitoBean
	private CouponIssueService couponIssueService;

	@Autowired
	private ConsistencyVerificationRunner runner;

	@Autowired
	private StockConsistencyCheck stockConsistencyCheck;

	@Autowired
	private DuplicateConsistencyCheck duplicateConsistencyCheck;

	@Autowired
	private NamedParameterJdbcTemplate jdbcTemplate;

	/**
	 * [검증 목적] 존재하지 않는 event_id로 Runner.run()을 호출하면 EventNotFoundException이
	 * 실제로 던져지는지, 그리고 그 예외 메시지에 문제의 event_id가 포함되는지 확인한다.
	 */
	@Test
	void 존재하지_않는_이벤트로_Runner를_호출하면_EventNotFoundException이_발생한다() {
		Long nonExistentEventId = findNonExistentEventId();
		List<ConsistencyCheck> checks = List.of(stockConsistencyCheck, duplicateConsistencyCheck);

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
		List<ConsistencyCheck> checks = List.of(stockConsistencyCheck, duplicateConsistencyCheck);

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
		Long existingEventId = findAnyEventId();
		List<ConsistencyCheck> checks = List.of(stockConsistencyCheck, duplicateConsistencyCheck);

		assertDoesNotThrow(() -> {
			var results = runner.run(checks, Scope.ofEvent(existingEventId), TriggerType.ON_DEMAND);
			assertEquals(2, results.size());
		});
	}

	// ----------------- 헬퍼 -----------------

	private Long findNonExistentEventId() {
		return jdbcTemplate.queryForObject(
				"SELECT COALESCE(MAX(event_id), 0) + 999999999 FROM coupon_event",
				Map.of(), Long.class);
	}

	private Long findAnyEventId() {
		return jdbcTemplate.queryForObject(
				"SELECT event_id FROM coupon_event ORDER BY event_id LIMIT 1",
				Map.of(), Long.class);
	}

	private long countVerificationResults() {
		Long count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM verification_result", Map.of(), Long.class);
		return count == null ? 0 : count;
	}
}
