package com.ace.coupon.service;

// 회차 하나의 집계 스냅샷 반영 결과
// 마감 처리는 NO_REDIS_STATE(상태만 CLOSED)와 UNREADABLE(다음 틱 재시도)을 반드시 구분
public enum CouponEventAggregateSnapshotResult {

	// 집계 컬럼이 실제로 바뀜
	APPLIED,

	// 조건부 UPDATE가 0행
	NOT_MODIFIED,

	// Redis에 판정 데이터가 없음
	// 값을 쓰면 안 되지만 회차 상태는 진행시킬 수 있다
	NO_REDIS_STATE,

	// Redis 조회 실패 또는 현황 값 손상
	UNREADABLE;

	public boolean isApplied() {
		return this == APPLIED;
	}

	// 값을 신뢰할 수 없어 스킵된 경우ddd
	public boolean isAnomaly() {
		return this == NO_REDIS_STATE || this == UNREADABLE;
	}
}
