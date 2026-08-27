package com.ace.consistency.recovery.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ace.common.ErrorCode;
import com.ace.common.exception.ConsistencyCheckException;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResult;
import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.recovery.RecoveryOutcome;
import com.ace.consistency.recovery.enums.RecoveryAction;

@ExtendWith(MockitoExtension.class)
class StockConsistencyRecoveryPolicyTest {

	private static final Long EVENT_ID = 1L;

	@Mock
	private StockEventRecoveryExecutor eventRecoveryExecutor;

	private StockConsistencyRecoveryPolicy policy;

	@BeforeEach
	void setUp() {
		policy = new StockConsistencyRecoveryPolicy(eventRecoveryExecutor);
	}

	private VerificationResultEntity failResultFor(Long eventId) {
		return VerificationResultEntity.from(VerificationResult.fail(
				"StockConsistencyCheck", TriggerType.ON_DEMAND, Scope.ofEvent(eventId),
				1, Map.of("eventId", eventId), LocalDateTime.now(), 10L));
	}

	@Test
	void EVENT_스코프_target이면_target의_eventId로_이벤트_하나만_복구한다() {
		RecoveryOutcome outcome = RecoveryOutcome.success(Scope.ofEvent(EVENT_ID), Map.of(), "복구완료");
		given(eventRecoveryExecutor.recoverEvent(EVENT_ID, RecoveryAction.STOCK_RECONCILE_COUNTER)).willReturn(outcome);

		List<RecoveryOutcome> outcomes = policy.recover(failResultFor(EVENT_ID), RecoveryAction.STOCK_RECONCILE_COUNTER);

		assertThat(outcomes).containsExactly(outcome);
	}

	@Test
	void ALL_스코프_target이면_diffDetail_sample에_있는_이벤트마다_executor를_호출해서_각각의_outcome을_반환한다() {
		Map<String, Object> diffDetail = Map.of("sample", List.of(
				Map.of("eventId", (Object) EVENT_ID),
				Map.of("eventId", (Object) 2L)));
		VerificationResultEntity allScopeTarget = VerificationResultEntity.from(VerificationResult.fail(
				"StockConsistencyCheck", TriggerType.SCHEDULED, Scope.all(LocalDateTime.now()),
				2, diffDetail, LocalDateTime.now(), 10L));

		RecoveryOutcome outcome1 = RecoveryOutcome.success(Scope.ofEvent(EVENT_ID), Map.of(), "이벤트 1 복구완료");
		RecoveryOutcome outcome2 = RecoveryOutcome.success(Scope.ofEvent(2L), Map.of(), "이벤트 2 복구완료");
		given(eventRecoveryExecutor.recoverEvent(EVENT_ID, RecoveryAction.STOCK_RECONCILE_COUNTER)).willReturn(outcome1);
		given(eventRecoveryExecutor.recoverEvent(2L, RecoveryAction.STOCK_RECONCILE_COUNTER)).willReturn(outcome2);

		List<RecoveryOutcome> outcomes = policy.recover(allScopeTarget, RecoveryAction.STOCK_RECONCILE_COUNTER);

		assertThat(outcomes).containsExactly(outcome1, outcome2);
	}

	@Test
	void ALL_스코프_target인데_diffDetail에_sample이_없으면_예외를_던진다() {
		VerificationResultEntity allScopeTarget = VerificationResultEntity.from(VerificationResult.fail(
				"StockConsistencyCheck", TriggerType.SCHEDULED, Scope.all(LocalDateTime.now()),
				0, Map.of(), LocalDateTime.now(), 10L));

		assertThatThrownBy(() -> policy.recover(allScopeTarget, RecoveryAction.STOCK_RECONCILE_COUNTER))
				.isInstanceOf(ConsistencyCheckException.class)
				.extracting(ex -> ((ConsistencyCheckException) ex).getErrorCode())
				.isEqualTo(ErrorCode.RECOVERY_TARGET_EVENTS_NOT_FOUND);
	}
}
