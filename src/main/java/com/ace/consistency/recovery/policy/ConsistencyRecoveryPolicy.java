package com.ace.consistency.recovery.policy;

import java.util.List;

import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.recovery.RecoveryOutcome;
import com.ace.consistency.recovery.enums.RecoveryAction;

/**
 * 특정 ConsistencyCheck 하나에 대한 복구 정책.
 * checkName()이 VerificationResultEntity.checkName과 일치하는 정책이
 * ConsistencyRecoveryDispatcher에 의해 선택되어 recover()가 호출된다.
 *
 * recover()는 내부에서 발생하는 예외를 직접 잡아서 RecoveryOutcome.failure()로 변환해야 한다.
 * Dispatcher가 이 호출과 RecoveryResult 저장을 하나의 트랜잭션으로 묶기 때문에, recover()가
 * 예외를 던진 채로 빠져나가면 트랜잭션 전체가 롤백되어 실패 이력조차 남길 수 없게 된다.
 *
 * Dispatcher는 target에서 복구 대상(이벤트 하나, 이벤트 여러 개, 개별 발급 건 등)을 판단하지
 * 않는다. 체크마다 위반 단위가 다르므로(예: StockConsistencyCheck는 이벤트 단위, 다른 체크는
 * 발급 건 단위일 수 있음), target의 diffDetail을 해석해 실제로 몇 건을, 무엇을 기준으로 복구할지
 * 정하는 것은 전적으로 정책의 몫이다. 정책은 그렇게 판단한 복구 단위마다 하나씩 RecoveryOutcome을
 * 만들어 리스트로 반환하고, Dispatcher는 그 리스트를 그대로 순회하며 각각 이력을 저장하고 재검증한다.
 */
public interface ConsistencyRecoveryPolicy {

	/** 이 정책이 담당하는 ConsistencyCheck의 이름 (ConsistencyCheck.getName()과 동일한 값). */
	String checkName();

	/** 이 정책이 처리할 수 있는 RecoveryAction 목록. 관리자 화면에서 선택지로 노출된다. */
	List<RecoveryAction> availableActions();

	List<RecoveryOutcome> recover(VerificationResultEntity target, RecoveryAction action);
}
