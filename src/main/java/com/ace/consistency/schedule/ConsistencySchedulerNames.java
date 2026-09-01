package com.ace.consistency.schedule;

/**
 * 동적 주기 변경 대상 스케줄러들의 식별 이름.
 * ConsistencySchedulerCoordinator에 등록/조회할 때 쓰는 키이자, SchedulerStartedEvent/CompletedEvent의
 * schedulerName과도 같은 값이어야 하므로 각 스케줄러 클래스가 이 상수를 그대로 참조한다.
 */
public final class ConsistencySchedulerNames {

	public static final String ALL = "ALL_CONSISTENCY";
	public static final String AS_OF_RANGE = "AS_OF_RANGE_CONSISTENCY";
	public static final String ORPHAN_VIOLATION_CLEANUP = "ORPHAN_VIOLATION_CLEANUP";

	private ConsistencySchedulerNames() {
	}
}
