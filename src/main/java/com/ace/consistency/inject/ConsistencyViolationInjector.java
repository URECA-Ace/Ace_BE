package com.ace.consistency.inject;

/**
 * 특정 ConsistencyCheck 하나가 잡아낼 수 있는 위반 데이터를 실제 DB(필요 시 Redis)에 직접
 * 심는 주입기. 시연/운영 확인 목적으로, 관리 화면에서 "위반 주입 -> 검증 실행 -> 복구 실행"을
 * 실제 데이터로 눈으로 확인할 수 있게 하기 위한 것이다 (테스트 코드가 아니라 운영 데이터 조작).
 *
 * checkName()이 ConsistencyCheck.getName()과 일치하는 주입기가 ConsistencyViolationInjectionDispatcher에
 * 의해 선택되어 inject()가 호출된다. ConsistencyRecoveryPolicy와 대칭되는 구조다.
 */
public interface ConsistencyViolationInjector {

	/** 이 주입기가 위반을 만들어내는 대상 ConsistencyCheck의 이름 (ConsistencyCheck.getName()과 동일한 값). */
	String checkName();

	/** 관리 화면에 노출할, 어떤 위반을 어떻게 만드는지에 대한 설명. */
	String description();

	/**
	 * eventId에 속한 데이터 중 하나를 골라 실제로 오염시킨다.
	 *
	 * @param eventId 위반을 심을 대상 이벤트
	 * @return 무엇을 어떻게 오염시켰는지에 대한 결과. 대상을 찾지 못하면
	 *         ConsistencyCheckException(ErrorCode.INJECTION_TARGET_NOT_FOUND)를 던진다.
	 */
	InjectionResult inject(Long eventId);
}
