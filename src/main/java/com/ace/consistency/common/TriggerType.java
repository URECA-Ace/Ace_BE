package com.ace.consistency.common;

/**
 * 검증이 어떤 트리거에 의해 실행되었는지 표시하는 라벨.
 * Runner의 실행 로직 자체에는 영향을 주지 않고, 결과 저장/조회 시 필터링 용도로만 사용된다.
 */
public enum TriggerType {
	EVENT_TRIGGER,        // coupon_event 상태 전이(SOLD_OUT/CLOSED) 시점에 자동 실행
	SCHEDULED,            // Spring Batch/Scheduler에 의한 주기 실행
	ON_DEMAND,            // Coupon State API를 통한 수동 실행
	RECOVERY_REVALIDATION // 복구 정책 실행 직후, 복구가 실제로 반영되었는지 확인하기 위한 재검증
}
