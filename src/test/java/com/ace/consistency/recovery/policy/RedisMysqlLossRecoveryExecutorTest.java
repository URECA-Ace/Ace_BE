package com.ace.consistency.recovery.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResult;
import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.recovery.RecoveryOutcome;
import com.ace.consistency.recovery.enums.RecoveryResultStatus;
import com.ace.coupon.persistence.relay.IssueStreamRelay;

/**
 * "확실히 복구 가능한 시나리오로만 실행 범위를 좁힌다"는 설계를 검증한다:
 * 릴레이 빈이 없거나 이미 정상 동작 중이면 아무 것도 바꾸지 않고 실패로 응답해야 하고,
 * 오직 "빈은 있는데 멈춰 있음" 한 가지 경우에만 실제로 start()를 호출해 복구를 시도한다.
 */
@ExtendWith(MockitoExtension.class)
class RedisMysqlLossRecoveryExecutorTest {

	private static final Long EVENT_ID = 42L;

	@Mock
	private ObjectProvider<IssueStreamRelay> relayProvider;
	@Mock
	private IssueStreamRelay relay;

	private VerificationResultEntity target() {
		return VerificationResultEntity.from(VerificationResult.fail(
				"RedisMysqlLossConsistencyCheck", TriggerType.ON_DEMAND, Scope.ofEvent(EVENT_ID),
				1, Map.of("eventId", EVENT_ID), List.of(), LocalDateTime.now(), 10L));
	}

	@Test
	void 릴레이_빈이_없으면_아무것도_하지_않고_실패로_응답한다() {
		given(relayProvider.getIfAvailable()).willReturn(null);
		RedisMysqlLossRecoveryExecutor executor = new RedisMysqlLossRecoveryExecutor(relayProvider);

		RecoveryOutcome outcome = executor.recover(target());

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		assertThat(outcome.getMessage()).contains("빈 자체가 존재하지 않습니다");
	}

	@Test
	void 릴레이가_이미_정상_동작중이면_아무것도_하지_않고_실패로_응답한다() {
		given(relayProvider.getIfAvailable()).willReturn(relay);
		given(relay.isRunning()).willReturn(true);
		RedisMysqlLossRecoveryExecutor executor = new RedisMysqlLossRecoveryExecutor(relayProvider);

		RecoveryOutcome outcome = executor.recover(target());

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		assertThat(outcome.getMessage()).contains("컨슈머 중단이 원인이 아니므로");
		verify(relay, never()).start();
	}

	@Test
	void 릴레이가_멈춰있으면_재시작을_시도하고_성공하면_성공으로_응답한다() {
		given(relayProvider.getIfAvailable()).willReturn(relay);
		given(relay.isRunning()).willReturn(false, true); // 재시작 전: false, 재시작 후: true
		RedisMysqlLossRecoveryExecutor executor = new RedisMysqlLossRecoveryExecutor(relayProvider);

		RecoveryOutcome outcome = executor.recover(target());

		verify(relay).start();
		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		assertThat(outcome.getRevalidationScope().getEventId()).isEqualTo(EVENT_ID);
	}

	@Test
	void 재시작을_시도했지만_기동에_실패하면_실패로_응답한다() {
		given(relayProvider.getIfAvailable()).willReturn(relay);
		given(relay.isRunning()).willReturn(false, false); // 재시작 후에도 계속 false
		RedisMysqlLossRecoveryExecutor executor = new RedisMysqlLossRecoveryExecutor(relayProvider);

		RecoveryOutcome outcome = executor.recover(target());

		verify(relay).start();
		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		assertThat(outcome.getMessage()).contains("기동에 실패했습니다");
	}
}
