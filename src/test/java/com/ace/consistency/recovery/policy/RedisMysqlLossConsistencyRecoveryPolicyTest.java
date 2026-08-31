package com.ace.consistency.recovery.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ace.common.exception.ConsistencyCheckException;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResult;
import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.recovery.RecoveryOutcome;
import com.ace.consistency.recovery.enums.RecoveryAction;

@ExtendWith(MockitoExtension.class)
class RedisMysqlLossConsistencyRecoveryPolicyTest {

	private static final Long EVENT_ID = 42L;

	@Mock
	private RedisMysqlLossRecoveryExecutor executor;

	private RedisMysqlLossConsistencyRecoveryPolicy policy;

	private VerificationResultEntity target() {
		return VerificationResultEntity.from(VerificationResult.fail(
				"RedisMysqlLossConsistencyCheck", TriggerType.ON_DEMAND, Scope.ofEvent(EVENT_ID),
				1, Map.of("eventId", EVENT_ID), List.of(), LocalDateTime.now(), 10L));
	}

	@Test
	void checkName은_RedisMysqlLossConsistencyCheck다() {
		policy = new RedisMysqlLossConsistencyRecoveryPolicy(executor);
		assertThat(policy.checkName()).isEqualTo("RedisMysqlLossConsistencyCheck");
	}

	@Test
	void 지원_액션은_RESTART_RELAY_CONSUMER_하나뿐이다() {
		policy = new RedisMysqlLossConsistencyRecoveryPolicy(executor);
		assertThat(policy.availableActions()).containsExactly(RecoveryAction.RESTART_RELAY_CONSUMER);
	}

	@Test
	void RESTART_RELAY_CONSUMER_요청이면_executor에게_그대로_위임한다() {
		policy = new RedisMysqlLossConsistencyRecoveryPolicy(executor);
		VerificationResultEntity target = target();
		RecoveryOutcome outcome = RecoveryOutcome.success(Scope.ofEvent(EVENT_ID), Map.of(), "재시작 완료");
		given(executor.recover(target)).willReturn(outcome);

		List<RecoveryOutcome> outcomes = policy.recover(target, RecoveryAction.RESTART_RELAY_CONSUMER);

		assertThat(outcomes).containsExactly(outcome);
	}

	@Test
	void 지원하지_않는_액션이면_예외를_던진다() {
		policy = new RedisMysqlLossConsistencyRecoveryPolicy(executor);
		assertThatThrownBy(() -> policy.recover(target(), RecoveryAction.STOCK_RECONCILE_COUNTER))
				.isInstanceOf(ConsistencyCheckException.class);
	}
}
