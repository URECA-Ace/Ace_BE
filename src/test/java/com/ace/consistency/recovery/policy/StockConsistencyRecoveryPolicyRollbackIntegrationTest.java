package com.ace.consistency.recovery.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.ace.consistency.check.ConsistencyCheckIntegrationTestBase;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResult;
import com.ace.consistency.common.ConsistencyCheck.Violation;
import com.ace.consistency.common.ViolationTargetType;
import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.entity.VerificationViolationEntity;
import com.ace.consistency.recovery.RecoveryOutcome;
import com.ace.consistency.recovery.enums.RecoveryAction;
import com.ace.consistency.recovery.enums.RecoveryResultStatus;
import com.ace.coupon.entity.CouponHistory;
import com.ace.coupon.repository.CouponHistoryRepository;
import com.ace.consistency.repository.VerificationResultRepository;
import com.ace.consistency.repository.VerificationViolationRepository;

/**
 * 여러 발급 건을 순차 회수하는 도중 예외가 발생했을 때, 앞서 처리한 변경까지 전부
 * 롤백되는지(부분 커밋이 남지 않는지)를 실제 트랜잭션 경계로 검증한다.
 */
class StockConsistencyRecoveryPolicyRollbackIntegrationTest extends ConsistencyCheckIntegrationTestBase {

	@MockitoBean
	private CouponHistoryRepository couponHistoryRepository;

	@Autowired
	private StockConsistencyRecoveryPolicy policy;
	@Autowired
	private VerificationResultRepository resultRepository;
	@Autowired
	private VerificationViolationRepository violationRepository;

	@Test
	@DisplayName("초과발급 회수 도중 예외가 발생하면 앞서 처리한 발급 건도 롤백되어 원래 상태로 남는다")
	void 회수_도중_예외가_발생하면_앞선_변경까지_전부_롤백된다() {
		long eventId = generateUniqueId();
		insertDummyEvent(eventId, 1, 3, -2);
		insertDummyIssue(eventId, 3);
		insertDummyIssue(eventId, 2);
		insertDummyIssue(eventId, 1);

		// 첫 번째 회수 대상(issue_sequence=3)의 History 저장은 성공하고,
		// 두 번째 회수 대상(issue_sequence=2)의 History 저장에서 장애가 발생하는 상황을 재현한다.
		given(couponHistoryRepository.save(any()))
				.willReturn(mock(CouponHistory.class))
				.willThrow(new RuntimeException("강제 실패 - History 저장 중 장애 발생"));

		VerificationResultEntity target = VerificationResultEntity.from(VerificationResult.fail(
				"StockConsistencyCheck", TriggerType.ON_DEMAND, Scope.ofEvent(eventId),
				1, Map.of("eventId", eventId), List.of(), LocalDateTime.now(), 10L));

		List<RecoveryOutcome> outcomes = policy.recover(target, RecoveryAction.STOCK_REVOKE_EXCESS_ISSUANCE);

		assertThat(outcomes).hasSize(1);
		RecoveryOutcome outcome = outcomes.get(0);
		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		assertThat(outcome.getMessage()).contains("재고 복구 중 오류가 발생했습니다");

		// 트랜잭션이 제대로 롤백됐다면, 먼저 처리되어 CANCELED로 바뀌었던 issue_sequence=3도
		// 커밋되지 않고 원래 상태(ISSUED)로 남아 있어야 한다.
		String firstProcessedStatus = jdbcTemplate.queryForObject(
				"SELECT status FROM coupon_issue WHERE event_id = :eventId AND issue_sequence = 3",
				new MapSqlParameterSource("eventId", eventId), String.class);
		assertThat(firstProcessedStatus).isEqualTo("ISSUED");

		Integer issuedQuantity = jdbcTemplate.queryForObject(
				"SELECT issued_quantity FROM coupon_event WHERE event_id = :eventId",
				new MapSqlParameterSource("eventId", eventId), Integer.class);
		assertThat(issuedQuantity).isEqualTo(3);
	}

	@Test
	@DisplayName("ALL 스코프로 여러 이벤트를 복구할 때, 한 이벤트의 예외는 그 이벤트만 롤백시키고 앞뒤 이벤트의 커밋에는 영향을 주지 않는다")
	void 이벤트_A_성공_B_예외_C_성공_시나리오에서_B만_롤백되고_A와_C는_각각_커밋된다() {
		long eventA = generateUniqueId();
		long eventB = generateUniqueId();
		long eventC = generateUniqueId();
		for (long eventId : List.of(eventA, eventB, eventC)) {
			insertDummyEvent(eventId, 1, 2, -1);
			insertDummyIssue(eventId, 2); // 초과분(회수 대상)
			insertDummyIssue(eventId, 1);
		}

		// 이벤트 A의 History 저장은 성공, 이벤트 B는 History 저장에서 장애, 이벤트 C는 다시 성공한다.
		given(couponHistoryRepository.save(any()))
				.willReturn(mock(CouponHistory.class))
				.willThrow(new RuntimeException("강제 실패 - 이벤트 B History 저장 중 장애 발생"))
				.willReturn(mock(CouponHistory.class));

		VerificationResultEntity target = resultRepository.save(VerificationResultEntity.from(VerificationResult.fail(
				"StockConsistencyCheck", TriggerType.SCHEDULED, Scope.all(LocalDateTime.now()),
				3, Map.of("violationCount", 3), List.of(), LocalDateTime.now(), 10L)));
		violationRepository.saveAll(List.of(eventA, eventB, eventC).stream()
				.map(eventId -> VerificationViolationEntity.forResult(target.getId(),
						new Violation(ViolationTargetType.EVENT, eventId, Map.of("eventId", eventId))))
				.toList());

		List<RecoveryOutcome> outcomes = policy.recover(target, RecoveryAction.STOCK_REVOKE_EXCESS_ISSUANCE);

		assertThat(outcomes).hasSize(3);
		assertThat(outcomes.get(0).getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS); // A
		assertThat(outcomes.get(1).getStatus()).isEqualTo(RecoveryResultStatus.FAIL); // B
		assertThat(outcomes.get(2).getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS); // C

		assertThat(issueStatus(eventA, 2)).isEqualTo("CANCELED");
		assertThat(issuedQuantity(eventA)).isEqualTo(1);

		// B는 History 저장 실패로 트랜잭션이 롤백되어, revoke() 호출로 메모리상 CANCELED였던 상태가
		// 커밋되지 않고 원래 상태(ISSUED)로 남아 있어야 한다.
		assertThat(issueStatus(eventB, 2)).isEqualTo("ISSUED");
		assertThat(issuedQuantity(eventB)).isEqualTo(2);

		// B의 롤백이 C의 처리나 커밋을 막지 않아야 한다.
		assertThat(issueStatus(eventC, 2)).isEqualTo("CANCELED");
		assertThat(issuedQuantity(eventC)).isEqualTo(1);
	}

	private String issueStatus(long eventId, int issueSequence) {
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("eventId", eventId)
				.addValue("issueSequence", issueSequence);
		return jdbcTemplate.queryForObject(
				"SELECT status FROM coupon_issue WHERE event_id = :eventId AND issue_sequence = :issueSequence",
				params, String.class);
	}

	private Integer issuedQuantity(long eventId) {
		return jdbcTemplate.queryForObject(
				"SELECT issued_quantity FROM coupon_event WHERE event_id = :eventId",
				new MapSqlParameterSource("eventId", eventId), Integer.class);
	}

	private void insertDummyEvent(long eventId, int totalStock, int issuedQuantity, int remainingStock) {
		String sql = """
				INSERT INTO coupon_event (event_id, coupon_id, round, open_at, close_at, total_stock, remaining_stock, issued_quantity, per_user_limit, status, created_at, updated_at)
				VALUES (:eventId, :eventId, 1, NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY), :totalStock, :remainingStock, :issuedQuantity, 1, 'OPEN', NOW(), NOW())
				""";
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("eventId", eventId)
				.addValue("totalStock", totalStock)
				.addValue("remainingStock", remainingStock)
				.addValue("issuedQuantity", issuedQuantity);
		jdbcTemplate.update(sql, params);
	}

	private void insertDummyIssue(long eventId, int issueSequence) {
		String sql = """
				INSERT INTO coupon_issue (event_id, user_id, issue_sequence, request_id, status, issued_at, valid_from, valid_to, created_at)
				VALUES (:eventId, :userId, :issueSequence, :requestId, 'ISSUED', NOW(), NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY), NOW())
				""";
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("eventId", eventId)
				.addValue("userId", generateUniqueId())
				.addValue("issueSequence", issueSequence)
				.addValue("requestId", "req-" + generateUniqueId());

		jdbcTemplate.update(sql, params);
	}
}
