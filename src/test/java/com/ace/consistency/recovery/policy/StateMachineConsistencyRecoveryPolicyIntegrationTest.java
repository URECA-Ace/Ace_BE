package com.ace.consistency.recovery.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willThrow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.ace.consistency.check.ConsistencyCheckIntegrationTestBase;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResult;
import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.recovery.RecoveryOutcome;
import com.ace.consistency.recovery.enums.RecoveryAction;
import com.ace.consistency.recovery.enums.RecoveryResultStatus;
import com.ace.coupon.entity.CouponHistory;
import com.ace.coupon.repository.CouponHistoryRepository;
import com.ace.coupon.repository.CouponIssueRepository;

/**
 * StateMachineConsistencyRecoveryPolicy는 이번에 추가된 것 중 유일하게 coupon_history 행을
 * 실제로 삭제하는 복구다. mock 기반 단위테스트(StateMachineConsistencyRecoveryPolicyTest)는
 * 분기 로직만 검증하므로, 실제 삭제/원복이 DB에 반영되는지와 "안전장치"가 실제로 아무것도
 * 지우지 않는지를 Testcontainers MySQL로 직접 확인한다.
 */
class StateMachineConsistencyRecoveryPolicyIntegrationTest extends ConsistencyCheckIntegrationTestBase {

	@Autowired
	private StateMachineConsistencyRecoveryPolicy policy;

	@Autowired
	private CouponHistoryRepository couponHistoryRepository;

	@Autowired
	private DataSource dataSource;

	// 실제 빈을 감싸는 스파이 — 특정 issueId 호출만 실패하도록 스텁하고, 나머지는 실제 DB로 위임한다.
	@MockitoSpyBean
	private CouponIssueRepository couponIssueRepository;

	@Test
	@DisplayName("삭제 범위에 USED/EXPIRED가 없으면 실제로 중복 이력이 삭제되고 status가 DB에 원복된다")
	void 안전한_붕괴_체인은_실제로_삭제되고_상태가_복구된다() {
		long eventId = generateUniqueId();
		insertDummyEvent(eventId);
		// status를 일부러 실제와 다른 값(USED)으로 심어서, restoreStatus()가 진짜로 DB를 바꿨는지
		// (원래 값이 우연히 같아서 통과한 게 아닌지) 구분할 수 있게 한다.
		long issueId = insertDummyIssue(eventId, "USED");
		insertDummyHistory(issueId, null, "ISSUED", "2024-01-01 10:00:00");
		insertDummyHistory(issueId, null, "ISSUED", "2024-01-01 10:00:01"); // 락 뚫림으로 중복 삽입된 이력

		VerificationResultEntity target = VerificationResultEntity.from(VerificationResult.fail(
				"StateMachineConsistencyCheck", TriggerType.ON_DEMAND, Scope.ofEvent(eventId),
				1, Map.of(), List.of(), LocalDateTime.now(), 10L));

		List<RecoveryOutcome> outcomes = policy.recover(target, RecoveryAction.DEFAULT);

		assertThat(outcomes).hasSize(1);
		assertThat(outcomes.get(0).getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		assertThat(outcomes.get(0).getDetail()).containsEntry("recoveredIssueIds", List.of(issueId));

		assertThat(historyCountOf(issueId)).isEqualTo(1L); // 중복 이력이 실제로 삭제됨
		assertThat(statusOf(issueId)).isEqualTo("ISSUED"); // USED로 심어뒀던 status가 ISSUED로 실제 복구됨
	}

	@Test
	@DisplayName("삭제 범위에 USED가 섞여있으면 실제로도 아무것도 지우거나 바꾸지 않고 회수 불가로 남긴다")
	void 안전하지_않은_붕괴_체인은_실제로_아무것도_바꾸지_않는다() {
		long eventId = generateUniqueId();
		insertDummyEvent(eventId);
		long issueId = insertDummyIssue(eventId, "ISSUED");
		insertDummyHistory(issueId, null, "ISSUED", "2024-01-01 10:00:00");
		insertDummyHistory(issueId, "EXPIRED", "USED", "2024-01-01 10:00:01"); // 연속성 붕괴 + tail에 USED 포함

		VerificationResultEntity target = VerificationResultEntity.from(VerificationResult.fail(
				"StateMachineConsistencyCheck", TriggerType.ON_DEMAND, Scope.ofEvent(eventId),
				1, Map.of(), List.of(), LocalDateTime.now(), 10L));

		List<RecoveryOutcome> outcomes = policy.recover(target, RecoveryAction.DEFAULT);

		assertThat(outcomes).hasSize(1);
		RecoveryOutcome outcome = outcomes.get(0);
		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		assertThat(outcome.getDetail()).containsEntry("notEligibleIssueIds", List.of(issueId));

		assertThat(historyCountOf(issueId)).isEqualTo(2L); // 아무 것도 삭제되지 않음
		assertThat(statusOf(issueId)).isEqualTo("ISSUED"); // 심어둔 값 그대로 — restoreStatus가 호출되지 않음
	}

	@Test
	@DisplayName("이벤트 하나에 이력이 많이 쌓여도(50개 발급 x 4건) 페이징 없이 전부 조회되고, 정상 체인이 붕괴로 오판되지 않는다")
	void 대량_이력에서도_정상_체인이_붕괴로_오판되지_않는다() {
		long eventId = generateUniqueId();
		insertDummyEvent(eventId);
		int issueCount = 50;
		for (int i = 0; i < issueCount; i++) {
			long issueId = insertDummyIssue(eventId, "USED");
			insertDummyHistory(issueId, null, "ISSUED", "2024-01-01 10:00:00");
			insertDummyHistory(issueId, "ISSUED", "USED", "2024-01-01 11:00:00");
			insertDummyHistory(issueId, "USED", "ISSUED", "2024-01-01 12:00:00");
			insertDummyHistory(issueId, "ISSUED", "USED", "2024-01-01 13:00:00");
		}

		long start = System.currentTimeMillis();
		List<CouponHistory> all = couponHistoryRepository.findAllByCouponEventIdOrderByIssueAndTime(eventId);
		long elapsedMs = System.currentTimeMillis() - start;

		assertThat(all).hasSize(issueCount * 4); // 페이징으로 잘리지 않고 전부 반환됨
		// 규모가 커져도 합리적인 시간 안에 끝나는지 보는 느슨한 가드 — 엄밀한 부하/성능 테스트는 아니다.
		assertThat(elapsedMs).isLessThan(5000);

		VerificationResultEntity target = VerificationResultEntity.from(VerificationResult.fail(
				"StateMachineConsistencyCheck", TriggerType.ON_DEMAND, Scope.ofEvent(eventId),
				0, Map.of(), List.of(), LocalDateTime.now(), 10L));

		List<RecoveryOutcome> outcomes = policy.recover(target, RecoveryAction.DEFAULT);

		assertThat(outcomes).hasSize(1);
		assertThat(outcomes.get(0).getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		// 정렬이 어긋났다면 findBreakIndex가 정상 체인 일부를 붕괴로 오판해 여기 잡혔을 것이다.
		assertThat(outcomes.get(0).getDetail()).containsEntry("recoveredIssueIds", List.of());
	}

	@Test
	@DisplayName("같은 이벤트에 대한 동시 복구 시도는 이벤트 행 락으로 직렬화된다")
	void 동시_복구_시도는_이벤트_락으로_직렬화된다() throws Exception {
		long eventId = generateUniqueId();
		insertDummyEvent(eventId);
		long issueId = insertDummyIssue(eventId, "USED");
		insertDummyHistory(issueId, null, "ISSUED", "2024-01-01 10:00:00");
		insertDummyHistory(issueId, null, "ISSUED", "2024-01-01 10:00:01"); // 중복 삽입된 이력

		VerificationResultEntity target = VerificationResultEntity.from(VerificationResult.fail(
				"StateMachineConsistencyCheck", TriggerType.ON_DEMAND, Scope.ofEvent(eventId),
				1, Map.of(), List.of(), LocalDateTime.now(), 10L));

		// 1) 별도 커넥션에서 이벤트 행을 미리 잠가서 "다른 관리자가 이미 복구를 진행 중"인 상황을 재현한다.
		try (Connection holderConn = dataSource.getConnection()) {
			holderConn.setAutoCommit(false);
			try (PreparedStatement ps = holderConn.prepareStatement(
					"SELECT * FROM coupon_event WHERE event_id = ? FOR UPDATE")) {
				ps.setLong(1, eventId);
				ps.executeQuery();
			}

			// 2) 락이 걸린 상태에서 policy.recover()를 별도 스레드로 호출한다 — 이 락 때문에 블로킹돼야 한다.
			ExecutorService executor = Executors.newSingleThreadExecutor();
			try {
				Future<List<RecoveryOutcome>> future = executor.submit(() -> policy.recover(target, RecoveryAction.DEFAULT));

				// 3) 락이 살아있는 동안에는 짧은 시간 안에 끝나지 않아야 한다 — 블로킹 증명.
				assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
						.isInstanceOf(TimeoutException.class);

				// 4) 보유하고 있던 락을 풀어준다.
				holderConn.rollback();

				// 5) 락이 풀리면 곧바로 완료되고, 결과도 정상이어야 한다.
				List<RecoveryOutcome> outcomes = future.get(5, TimeUnit.SECONDS);
				assertThat(outcomes).hasSize(1);
				assertThat(outcomes.get(0).getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
				assertThat(historyCountOf(issueId)).isEqualTo(1L);
				assertThat(statusOf(issueId)).isEqualTo("ISSUED");
			} finally {
				executor.shutdown();
			}
		}
	}

	@Test
	@DisplayName("루프 도중 예외가 발생하면 앞서 처리한 issue의 변경까지 전부 롤백된다")
	void 도중_예외가_발생하면_앞서_처리한_변경까지_전부_롤백된다() {
		long eventId = generateUniqueId();
		insertDummyEvent(eventId);

		// issue_id가 더 작아서 루프에서 먼저 처리되는 안전한 붕괴 — 정상적이라면 복구되어야 한다.
		long issueA = insertDummyIssue(eventId, "USED");
		insertDummyHistory(issueA, null, "ISSUED", "2024-01-01 10:00:00");
		insertDummyHistory(issueA, null, "ISSUED", "2024-01-01 10:00:01");

		// issue_id가 더 커서 나중에 처리되는, 강제로 예외를 일으킬 대상 — 역시 안전한 붕괴라 정상이면 복구 대상.
		long issueB = insertDummyIssue(eventId, "USED");
		insertDummyHistory(issueB, null, "ISSUED", "2024-01-01 10:00:00");
		insertDummyHistory(issueB, null, "ISSUED", "2024-01-01 10:00:01");

		// given(spy.method()) 형태는 스텁 설정 과정에서 실제 메서드를 먼저 호출해버려(트랜잭션
		// 밖이라 TransactionRequiredException 발생) 스파이에는 doThrow 계열(BDD로는 willThrow().given())을 써야 한다.
		willThrow(new RuntimeException("강제 실패 - issueB 처리 중 장애 재현"))
				.given(couponIssueRepository).findByIdForUpdate(issueB);

		VerificationResultEntity target = VerificationResultEntity.from(VerificationResult.fail(
				"StateMachineConsistencyCheck", TriggerType.ON_DEMAND, Scope.ofEvent(eventId),
				2, Map.of(), List.of(), LocalDateTime.now(), 10L));

		List<RecoveryOutcome> outcomes = policy.recover(target, RecoveryAction.DEFAULT);

		assertThat(outcomes).hasSize(1);
		assertThat(outcomes.get(0).getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		assertThat(outcomes.get(0).getMessage()).contains("오류가 발생했습니다");

		// 트랜잭션이 제대로 롤백됐다면, 먼저 처리되어 삭제/원복됐던 issueA도 커밋되지 않고 원래 상태로 남는다.
		assertThat(historyCountOf(issueA)).isEqualTo(2L);
		assertThat(statusOf(issueA)).isEqualTo("USED");
	}

	@Test
	@DisplayName("일부만 회수 가능한 이벤트는 전체가 FAIL로 보고되어도 회수 가능했던 건은 실제로 커밋된다")
	void 부분_회수_성공_건은_회수불가_건이_섞여있어도_실제로_커밋된다() {
		long eventId = generateUniqueId();
		insertDummyEvent(eventId);

		long recoverableIssueId = insertDummyIssue(eventId, "USED");
		insertDummyHistory(recoverableIssueId, null, "ISSUED", "2024-01-01 10:00:00");
		insertDummyHistory(recoverableIssueId, null, "ISSUED", "2024-01-01 10:00:01"); // 안전한 중복 붕괴

		long notEligibleIssueId = insertDummyIssue(eventId, "ISSUED");
		insertDummyHistory(notEligibleIssueId, null, "ISSUED", "2024-01-01 10:00:00");
		insertDummyHistory(notEligibleIssueId, "EXPIRED", "USED", "2024-01-01 10:00:01"); // tail에 USED 포함 - 회수 불가

		VerificationResultEntity target = VerificationResultEntity.from(VerificationResult.fail(
				"StateMachineConsistencyCheck", TriggerType.ON_DEMAND, Scope.ofEvent(eventId),
				2, Map.of(), List.of(), LocalDateTime.now(), 10L));

		List<RecoveryOutcome> outcomes = policy.recover(target, RecoveryAction.DEFAULT);

		assertThat(outcomes).hasSize(1);
		RecoveryOutcome outcome = outcomes.get(0);
		// notEligibleIssueId가 하나라도 있으면 이벤트 전체는 FAIL로 보고된다 (RecoveryOutcome 수준의 이분법).
		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		assertThat(outcome.getDetail()).containsEntry("recoveredIssueIds", List.of(recoverableIssueId));
		assertThat(outcome.getDetail()).containsEntry("notEligibleIssueIds", List.of(notEligibleIssueId));

		// 하지만 DB 레벨에서는 회수 가능했던 건이 실제로 커밋되어 있어야 한다 — FAIL이라고 롤백되지 않는다.
		assertThat(historyCountOf(recoverableIssueId)).isEqualTo(1L);
		assertThat(statusOf(recoverableIssueId)).isEqualTo("ISSUED");

		// 회수 불가 건은 손대지 않은 원래 상태 그대로.
		assertThat(historyCountOf(notEligibleIssueId)).isEqualTo(2L);
		assertThat(statusOf(notEligibleIssueId)).isEqualTo("ISSUED");
	}

	// recoverBrokenChains()가 맨 먼저 거는 이벤트 락(findByIdForUpdate) 대상. FOREIGN_KEY_CHECKS=0라
	// 원래는 없어도 coupon_issue insert 자체는 되지만, 이 락이 걸리려면 실제 row가 있어야 한다.
	private void insertDummyEvent(long eventId) {
		String sql = """
				INSERT INTO coupon_event (event_id, coupon_id, round, open_at, close_at, total_stock, remaining_stock, issued_quantity, per_user_limit, status, created_at, updated_at)
				VALUES (:eventId, :eventId, 1, NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY), 1000, 1000, 0, 1, 'OPEN', NOW(), NOW())
				""";
		jdbcTemplate.update(sql, new MapSqlParameterSource("eventId", eventId));
	}

	private Long historyCountOf(long issueId) {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM coupon_history WHERE issue_id = :issueId",
				new MapSqlParameterSource("issueId", issueId), Long.class);
	}

	private String statusOf(long issueId) {
		return jdbcTemplate.queryForObject(
				"SELECT status FROM coupon_issue WHERE issue_id = :issueId",
				new MapSqlParameterSource("issueId", issueId), String.class);
	}

	private long insertDummyIssue(long eventId, String status) {
		String sql = """
				INSERT INTO coupon_issue (event_id, user_id, issue_sequence, request_id, status, issued_at, valid_from, valid_to, created_at)
				VALUES (:eventId, :userId, :issueSequence, :requestId, :status, NOW(), NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY), NOW())
				""";
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("eventId", eventId)
				.addValue("userId", generateUniqueId())
				.addValue("issueSequence", generateUniqueId())
				.addValue("requestId", "req-" + generateUniqueId())
				.addValue("status", status);

		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcTemplate.update(sql, params, keyHolder, new String[]{"issue_id"});
		return keyHolder.getKey().longValue();
	}

	private void insertDummyHistory(long issueId, String fromStatus, String toStatus, String occurredAt) {
		String sql = """
				INSERT INTO coupon_history (issue_id, from_status, to_status, occurred_at, recorded_at)
				VALUES (:issueId, :fromStatus, :toStatus, :occurredAt, NOW())
				""";
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("issueId", issueId)
				.addValue("fromStatus", fromStatus)
				.addValue("toStatus", toStatus)
				.addValue("occurredAt", occurredAt);
		jdbcTemplate.update(sql, params);
	}
}
