package com.ace.consistency.common;

/**
 * VerificationViolationEntity(위반 1건 = 행 1개)가 어떤 종류의 대상을 위반했는지 나타낸다.
 * 검증마다 위반의 자연스러운 단위(이벤트/발급 건/이력)가 다르므로, targetId 컬럼 하나를
 * 공용으로 쓰되 이 enum으로 그 값이 무엇을 가리키는지 구분한다.
 */
public enum ViolationTargetType {
	EVENT,
	ISSUE,
	HISTORY
}
