package com.ace;

import com.ace.consistency.check.ConsistencyCheckIntegrationTestBase;
import com.ace.consistency.check.StockConsistencyCheck;
import com.ace.consistency.check.StateMachineConsistencyCheck;
import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.ConsistencyVerificationRunner;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.ViolationTargetType;
import com.ace.coupon.service.CouponIssueService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConsistencyVerificationRunner.runAsync()가 실제로 Spring Batch Job을 통해
 * ALL 스코프 정합성 검증을 수행하는지 확인하는 통합 테스트.
 *
 * ConsistencyCheckIntegrationTestBase가 띄우는 Testcontainers MySQL에 대고 실행하며,
 * 배치는 별도 스레드(TaskExecutorJobOperator)에서 비동기로 실행되므로 verification_result
 * 테이블에 결과가 쌓일 때까지 폴링(awaitCompletion)해서 기다린다.
 *
 * 각 테스트는 실제 DB에 verification_result 행을 커밋하므로(배치가 별도 스레드/트랜잭션에서
 * 돌기 때문에 테스트 트랜잭션 롤백으로는 정리되지 않는다), afterEach에서 테스트가 만든 행만
 * id 기준으로 직접 지운다.
 */
class ConsistencyVerificationRunnerBatchTest extends ConsistencyCheckIntegrationTestBase {

	// Redis + Lua 기반 실제 구현체가 추가되기 전까지 전체 Context에서만 대체한다.
	@MockitoBean
	private CouponIssueService couponIssueService;

	@Autowired
	private ConsistencyVerificationRunner runner;

	@Autowired
	private StockConsistencyCheck stockConsistencyCheck;

	@Autowired
	private StateMachineConsistencyCheck stateMachineConsistencyCheck;

	@Autowired
	private RestartableFlakyConsistencyCheck flakyConsistencyCheck;

	@Autowired
	private JobRepository jobRepository;

	private Long maxIdBefore;
	private Long dummyEventId;
	private final List<Long> additionalEventIds = new ArrayList<>();

	// 저장 결과를 직접 확인하기 위해 임시로 정리(cleanup)를 꺼둔 상태입니다. 확인이 끝나면 주석을 해제해주세요.
	// @AfterEach
	// void cleanUp() {
	// 	if (maxIdBefore != null) {
	// 		jdbcTemplate.update("DELETE FROM verification_result WHERE id > :maxId", Map.of("maxId", maxIdBefore));
	// 		maxIdBefore = null;
	// 	}
	// }

	/**
	 * ALL 스코프 배치는 EventIdPageReader가 coupon_event를 페이징하면서 읽은 event_id가 있을
	 * 때만 각 Step의 Check(processor)를 호출한다. coupon_event가 비어 있으면 Check 자체가
	 * 한 번도 실행되지 않아 예외/실패를 검증하는 테스트가 무의미해지므로, 이벤트 한 건을 미리 심어둔다.
	 */
	@BeforeEach
	void setUpEvent() {
		dummyEventId = insertDummyEvent();
	}

	@AfterEach
	void cleanUpEvent() {
		flakyConsistencyCheck.resetTestState();
		if (!additionalEventIds.isEmpty()) {
			jdbcTemplate.update("DELETE FROM coupon_event WHERE event_id IN (:eventIds)",
					new MapSqlParameterSource("eventIds", additionalEventIds));
			additionalEventIds.clear();
		}
		if (dummyEventId != null) {
			jdbcTemplate.update("DELETE FROM coupon_event WHERE event_id = :eventId",
					new MapSqlParameterSource("eventId", dummyEventId));
			dummyEventId = null;
		}
	}

	private Long insertDummyEvent() {
		long eventId = generateUniqueId();
		String sql = """
				INSERT INTO coupon_event (event_id, coupon_id, round, open_at, close_at, total_stock, remaining_stock, issued_quantity, per_user_limit, status, created_at, updated_at)
				VALUES (:eventId, :eventId, 1, NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY), 100, 100, 0, 1, 'OPEN', NOW(), NOW())
				""";
		jdbcTemplate.update(sql, new MapSqlParameterSource("eventId", eventId));
		return eventId;
	}

	private void ensureAtLeastTenEventPages() {
		Integer existing = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM coupon_event", Map.of(), Integer.class);
		int required = Math.max(0, 101 - (existing == null ? 0 : existing));
		for (int index = 0; index < required; index++) {
			additionalEventIds.add(insertDummyEvent());
		}
	}

	/**
	 * [검증 목적] 실제 StockConsistencyCheck/StateMachineConsistencyCheck 두 개를 ALL 스코프로 넣고
	 * runAsync()를 호출했을 때, Job이 정상적으로 두 Step을 모두 순차 실행하고
	 * 각 Step 결과가 verification_result 테이블에 저장되는지 확인한다.
	 * (심어둔 이벤트에는 coupon_issue가 없으므로 두 Check 모두 위반이 없는 상태다)
	 */
	@Test
	void 두_Check_모두_정상적으로_실행되고_결과가_저장된다() {
		maxIdBefore = maxVerificationResultId();
		List<ConsistencyCheck> checks = List.of(stockConsistencyCheck, stateMachineConsistencyCheck);

		JobExecution execution = runner.runAsync(checks, Scope.all(LocalDateTime.now()), TriggerType.ON_DEMAND);
		JobExecution finished = awaitCompletion(execution);

		assertEquals(BatchStatus.COMPLETED, finished.getStatus());
		assertEquals(2, finished.getStepExecutions().size());
		assertEquals(BatchStatus.COMPLETED, stepOf(finished, "StockConsistencyCheckStep").getStatus());
		assertEquals(BatchStatus.COMPLETED, stepOf(finished, "StateMachineConsistencyCheckStep").getStatus());

		List<Map<String, Object>> rows = fetchResultsAfter(maxIdBefore);
		assertEquals(2, rows.size());
		assertTrue(rows.stream().allMatch(r -> "PASS".equals(r.get("status"))),
				"현재 더미데이터는 위반이 없어야 하는데 FAIL/ERROR 결과가 저장됐습니다: " + rows);
	}

	/**
	 * [검증 목적] 앞 Step에서 예외가 발생하면(정합성 위반이 아니라 Check 실행 자체가 실패하면)
	 * Job이 FAILED로 끝나고, 뒤에 연결된 Step은 아예 시작조차 되지 않는지 확인한다.
	 */
	@Test
	void 앞_Step이_예외로_실패하면_뒤_Step은_실행되지_않는다() {
		maxIdBefore = maxVerificationResultId();
		ConsistencyCheck throwingCheck = new ThrowingConsistencyCheck("FakeThrowingCheck");
		List<ConsistencyCheck> checks = List.of(throwingCheck, stateMachineConsistencyCheck);

		JobExecution execution = runner.runAsync(checks, Scope.all(LocalDateTime.now()), TriggerType.ON_DEMAND);
		JobExecution finished = awaitCompletion(execution);

		assertEquals(BatchStatus.FAILED, finished.getStatus());
		assertEquals(1, finished.getStepExecutions().size(),
				"뒤 Step(StateMachineConsistencyCheckStep)은 아예 시작되지 않아야 합니다.");
		assertEquals(BatchStatus.FAILED, stepOf(finished, "FakeThrowingCheckStep").getStatus());

		List<Map<String, Object>> rows = fetchResultsAfter(maxIdBefore);
		assertEquals(1, rows.size(), "실행되지 않은 뒤 Step의 결과가 저장되면 안 됩니다.");
		assertEquals("ERROR", rows.get(0).get("status"));
		assertEquals("FakeThrowingCheck", rows.get(0).get("check_name"));
	}

	/**
	 * [검증 목적] 앞 Step에서 "정합성 위반"(FAIL, 예외 아님)이 나와도 Step 자체는 정상 종료되므로
	 * Job은 계속 진행되어 뒤 Step도 정상적으로 실행/저장되는지 확인한다.
	 */
	@Test
	void 앞_Step의_정합성_실패는_뒤_Step_실행을_막지_않는다() {
		maxIdBefore = maxVerificationResultId();
		ConsistencyCheck failingCheck = new FailingConsistencyCheck("FakeFailingCheck");
		List<ConsistencyCheck> checks = List.of(failingCheck, stateMachineConsistencyCheck);

		JobExecution execution = runner.runAsync(checks, Scope.all(LocalDateTime.now()), TriggerType.ON_DEMAND);
		JobExecution finished = awaitCompletion(execution);

		assertEquals(BatchStatus.COMPLETED, finished.getStatus(),
				"정합성 FAIL은 예외가 아니므로 Job 전체는 COMPLETED여야 합니다.");
		assertEquals(2, finished.getStepExecutions().size());
		assertEquals(BatchStatus.COMPLETED, stepOf(finished, "FakeFailingCheckStep").getStatus());
		assertEquals(BatchStatus.COMPLETED, stepOf(finished, "StateMachineConsistencyCheckStep").getStatus());

		List<Map<String, Object>> rows = fetchResultsAfter(maxIdBefore);
		assertEquals(2, rows.size());
		Map<String, Object> failingResult = rows.stream()
				.filter(r -> "FakeFailingCheck".equals(r.get("check_name")))
				.findFirst().orElseThrow();
		Map<String, Object> stateMachineResult = rows.stream()
				.filter(r -> "StateMachineConsistencyCheck".equals(r.get("check_name")))
				.findFirst().orElseThrow();

		assertEquals("FAIL", failingResult.get("status"));
		assertEquals("PASS", stateMachineResult.get("status"));
	}

	/**
	 * [검증 목적] Step이 예외로 실패해 Job이 FAILED로 끝난 뒤, restartRunAsync()로
	 * 같은 checks/scope/triggerType을 재구성해 재시작하면 실패했던 Step부터 이어서 실행되어
	 * 결국 Job 전체가 COMPLETED로 끝나는지, 그리고 같은 JobInstance를 이어가는지 확인한다.
	 */
	@Test
	void 실패한_Job을_재시작하면_이어서_실행되어_완료된다() {
		maxIdBefore = maxVerificationResultId();
		flakyConsistencyCheck.throwOnNextCall();
		List<ConsistencyCheck> checks = List.of(flakyConsistencyCheck, stateMachineConsistencyCheck);

		JobExecution firstExecution = runner.runAsync(checks, Scope.all(LocalDateTime.now()), TriggerType.ON_DEMAND);
		JobExecution firstFinished = awaitCompletion(firstExecution);

		assertEquals(BatchStatus.FAILED, firstFinished.getStatus());
		assertEquals(1, firstFinished.getStepExecutions().size(),
				"뒤 Step(StateMachineConsistencyCheckStep)은 아직 실행되지 않아야 합니다.");

		JobExecution restarted = runner.restartRunAsync(firstFinished.getId());
		JobExecution restartedFinished = awaitCompletion(restarted);

		assertNotEquals(firstFinished.getId(), restartedFinished.getId(), "재시작은 새 JobExecution을 만들어야 합니다.");
		assertEquals(firstFinished.getJobInstanceId(), restartedFinished.getJobInstanceId(),
				"재시작이어도 같은 JobInstance를 이어가야 합니다.");
		assertEquals(BatchStatus.COMPLETED, restartedFinished.getStatus());
		assertEquals(2, restartedFinished.getStepExecutions().size());
		assertEquals(BatchStatus.COMPLETED, stepOf(restartedFinished, "RestartableFlakyConsistencyCheckStep").getStatus());
		assertEquals(BatchStatus.COMPLETED, stepOf(restartedFinished, "StateMachineConsistencyCheckStep").getStatus());

		List<Map<String, Object>> rows = fetchResultsAfter(maxIdBefore);
		assertEquals(3, rows.size(), "1차 실행의 ERROR 1건 + 재시작 후 PASS 2건이 저장돼야 합니다: " + rows);
		long errorCount = rows.stream().filter(r -> "ERROR".equals(r.get("status"))).count();
		long passCount = rows.stream().filter(r -> "PASS".equals(r.get("status"))).count();
		assertEquals(1, errorCount);
		assertEquals(2, passCount);
	}

	@Test
	void 첫_청크의_violation은_Job_재시작_후_최종_결과에_연결된다() {
		ensureAtLeastTenEventPages();
		maxIdBefore = maxVerificationResultId();
		flakyConsistencyCheck.failAfterAccumulatingViolations();

		JobExecution firstExecution = runner.runAsync(
				List.of(flakyConsistencyCheck), Scope.all(LocalDateTime.now()), TriggerType.ON_DEMAND);
		JobExecution firstFinished = awaitCompletion(firstExecution);

		assertEquals(BatchStatus.FAILED, firstFinished.getStatus());
		List<Map<String, Object>> temporaryRows = jdbcTemplate.queryForList("""
				SELECT id, verification_result_id, batch_job_instance_id, batch_step_name
				FROM verification_violation
				WHERE batch_job_instance_id = :jobInstanceId
				  AND batch_step_name = 'RestartableFlakyConsistencyCheckStep'
				ORDER BY id
				""", new MapSqlParameterSource("jobInstanceId", firstFinished.getJobInstanceId()));
		assertEquals(5, temporaryRows.size());
		List<Long> violationIds = temporaryRows.stream().map(row -> {
			assertNull(row.get("verification_result_id"));
			assertEquals(firstFinished.getJobInstanceId(),
					((Number) row.get("batch_job_instance_id")).longValue());
			return ((Number) row.get("id")).longValue();
		}).toList();

		JobExecution restartedFinished = awaitCompletion(runner.restartRunAsync(firstFinished.getId()));

		assertEquals(BatchStatus.COMPLETED, restartedFinished.getStatus());
		assertEquals(firstFinished.getJobInstanceId(), restartedFinished.getJobInstanceId());
		Map<String, Object> finalResult = fetchResultsAfter(maxIdBefore).stream()
				.filter(row -> "RestartableFlakyConsistencyCheck".equals(row.get("check_name")))
				.filter(row -> "FAIL".equals(row.get("status")))
				.findFirst()
				.orElseThrow();
		assertEquals(5, ((Number) finalResult.get("violation_count")).intValue());

		List<Map<String, Object>> linkedRows = jdbcTemplate.queryForList("""
				SELECT verification_result_id, batch_job_instance_id, batch_step_name
				FROM verification_violation WHERE id IN (:ids)
				""", new MapSqlParameterSource("ids", violationIds));
		assertEquals(5, linkedRows.size());
		for (Map<String, Object> linked : linkedRows) {
			assertEquals(((Number) finalResult.get("id")).longValue(),
					((Number) linked.get("verification_result_id")).longValue());
			assertNull(linked.get("batch_job_instance_id"));
			assertNull(linked.get("batch_step_name"));
		}
	}

	// ----------------- 테스트 전용 가짜 Check -----------------

	private static final class ThrowingConsistencyCheck implements ConsistencyCheck {
		private final String name;

		private ThrowingConsistencyCheck(String name) {
			this.name = name;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public Set<Scope.ScopeType> supportedScopeTypes() {
			return Set.of(Scope.ScopeType.ALL);
		}

		@Override
		public CheckOutcome check(Scope scope) {
			throw new RuntimeException("테스트용 강제 예외");
		}
	}

	private static final class FailingConsistencyCheck implements ConsistencyCheck {
		private final String name;

		private FailingConsistencyCheck(String name) {
			this.name = name;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public Set<Scope.ScopeType> supportedScopeTypes() {
			return Set.of(Scope.ScopeType.ALL);
		}

		@Override
		public CheckOutcome check(Scope scope) {
			return CheckOutcome.fail(1, Map.of("violationCount", 1), List.of(
					new Violation(ViolationTargetType.EVENT, 1L, Map.of("reason", "테스트용 강제 실패"))));
		}
	}

	// ----------------- 헬퍼 -----------------

	/**
	 * runAsync()는 Job을 별도 스레드에서 시작만 시키고 바로 반환하므로, JobRepository에서
	 * 최신 상태를 다시 읽어와 더 이상 실행 중이 아닐 때까지(성공/실패 확정될 때까지) 기다린다.
	 */
	private JobExecution awaitCompletion(JobExecution execution) {
		long deadline = System.currentTimeMillis() + 30_000;
		JobExecution latest = execution;
		while (latest.getStatus().isRunning()) {
			if (System.currentTimeMillis() > deadline) {
				fail("배치 Job이 제한 시간(30s) 내에 끝나지 않았습니다. status=" + latest.getStatus());
			}
			try {
				Thread.sleep(200);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				fail("대기 중 인터럽트가 발생했습니다.");
			}
			latest = jobRepository.getJobExecution(execution.getId());
		}
		return latest;
	}

	private StepExecution stepOf(JobExecution execution, String stepName) {
		return execution.getStepExecutions().stream()
				.filter(step -> step.getStepName().equals(stepName))
				.findFirst()
				.orElseThrow(() -> new AssertionError(stepName + " Step이 실행되지 않았습니다."));
	}

	private Long maxVerificationResultId() {
		Long max = jdbcTemplate.queryForObject(
				"SELECT COALESCE(MAX(id), 0) FROM verification_result", Map.of(), Long.class);
		return max == null ? 0L : max;
	}

	private List<Map<String, Object>> fetchResultsAfter(Long maxIdBefore) {
		return jdbcTemplate.queryForList(
				"SELECT * FROM verification_result WHERE id > :maxId ORDER BY id", Map.of("maxId", maxIdBefore));
	}
}
