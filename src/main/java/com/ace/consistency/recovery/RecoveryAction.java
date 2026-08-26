package com.ace.consistency.recovery;

/**
 * 관리자가 위반 결과에 대해 선택하는 복구 방식.
 * 체크마다 필요한 액션의 의미가 다르므로, 새로운 체크에 복구 정책이 추가될 때마다
 * 그 체크 전용 액션 상수를 이곳에 추가한다. 값 자체를 어떻게 해석할지(무엇을 어떻게 복구할지)는
 * 각 ConsistencyRecoveryPolicy 구현체의 책임이다.
 */
public enum RecoveryAction {

	/** 별도의 세부 방식 선택 없이 정책이 유일한 복구 절차만 수행하는 경우 사용하는 기본 액션. */
	DEFAULT
}
