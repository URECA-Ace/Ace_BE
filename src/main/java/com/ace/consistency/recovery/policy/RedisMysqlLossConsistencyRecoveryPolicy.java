package com.ace.consistency.recovery.policy;

import java.util.List;

import org.springframework.stereotype.Component;

import com.ace.common.ErrorCode;
import com.ace.common.exception.ConsistencyCheckException;
import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.recovery.RecoveryOutcome;
import com.ace.consistency.recovery.enums.RecoveryAction;

import lombok.RequiredArgsConstructor;

/**
 * RedisMysqlLossConsistencyCheck의 복구 정책.
 *
 * 이 체크가 잡는 위반은 "릴레이 컨슈머 정지"(RESTART_RELAY_CONSUMER로 확실히 복구 가능),
 * "MySQL 장애", "Redis 데이터 유실", "릴레이 소비 대상 범위 이탈" 등 원인이 다양한데, 위반 결과
 * 자체(집계 카운트 차이)만으로는 어느 원인인지 구분할 수 없다. 그래서 액션 자체는 하나만 제공하고,
 * 실제 판별은 RedisMysqlLossRecoveryExecutor가 릴레이의 현재 상태를 직접 확인해서 수행한다 -
 * 판별 결과 릴레이 정지가 원인이 아니면 아무 것도 바꾸지 않고 실패로 응답해 관리자가 직접
 * 조사하게 한다("확실히 회복 가능한 시나리오로만 실행 범위를 좁힌다").
 */
@Component
@RequiredArgsConstructor
public class RedisMysqlLossConsistencyRecoveryPolicy implements ConsistencyRecoveryPolicy {

	private static final String CHECK_NAME = "RedisMysqlLossConsistencyCheck";

	private final RedisMysqlLossRecoveryExecutor executor;

	@Override
	public String checkName() {
		return CHECK_NAME;
	}

	@Override
	public List<RecoveryAction> availableActions() {
		return List.of(RecoveryAction.RESTART_RELAY_CONSUMER);
	}

	@Override
	public List<RecoveryOutcome> recover(VerificationResultEntity target, RecoveryAction action) {
		if (action != RecoveryAction.RESTART_RELAY_CONSUMER) {
			throw new ConsistencyCheckException(ErrorCode.RECOVERY_NOT_APPLICABLE,
					"RedisMysqlLossConsistencyCheck는 RESTART_RELAY_CONSUMER 액션이 필요합니다.");
		}
		return List.of(executor.recover(target));
	}
}
