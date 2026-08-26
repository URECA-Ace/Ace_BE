package com.ace.consistency.recovery;

import com.ace.consistency.entity.VerificationResultEntity;

/**
 * 특정 ConsistencyCheck 하나에 대한 복구 정책.
 * checkName()이 VerificationResultEntity.checkName과 일치하는 정책이
 * ConsistencyRecoveryDispatcher에 의해 선택되어 recover()가 호출된다.
 *
 * recover()는 내부에서 발생하는 예외를 직접 잡아서 RecoveryOutcome.failure()로 변환해야 한다.
 * Dispatcher가 이 호출과 RecoveryResult 저장을 하나의 트랜잭션으로 묶기 때문에, recover()가
 * 예외를 던진 채로 빠져나가면 트랜잭션 전체가 롤백되어 실패 이력조차 남길 수 없게 된다.
 */
public interface ConsistencyRecoveryPolicy {

	/** 이 정책이 담당하는 ConsistencyCheck의 이름 (ConsistencyCheck.getName()과 동일한 값). */
	String checkName();

	RecoveryOutcome recover(VerificationResultEntity target, RecoveryAction action);
}
