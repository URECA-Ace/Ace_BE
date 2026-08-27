package com.ace.coupon.service;

// 회차 하나의 집계 스냅샷 반영 결과
// 마감 처리는 NO_REDIS_STATE(상태만 CLOSED)와 UNREADABLE(다음 틱 재시도)을 반드시 구분
public enum CouponEventAggregateSnapshotResult {

	// 집계 컬럼이 실제로 바뀜
	APPLIED,

	// 이미 같은 값이 반영돼 있어 바꿀 것이 없음
	// 집계는 확정된 상태
	ALREADY_APPLIED,

	// 조건부 UPDATE 가 거부됨
	// 재고 설정 불일치(다른 회차의 Redis 값), 확정 수 역행, 회차 없음
	// 집계를 신뢰할 수 없으므로 상태를 진행시키면 안 된다
	REJECTED,

	// Redis에 판정 데이터가 없음
	// 값을 쓰면 안 되지만 회차 상태는 진행시킬 수 있다
	NO_REDIS_STATE,

	// Redis 조회 실패 또는 현황 값 손상
	UNREADABLE;

	// 집계가 이번 스냅샷 기준으로 확정됐는가
	// 상태 전환의 전제 조건
	public boolean isAggregateFinalized() {
		return this == APPLIED || this == ALREADY_APPLIED;
	}

	// 값을 신뢰할 수 없어 스킵된 경우
	public boolean isAnomaly() {
		return this == REJECTED || this == NO_REDIS_STATE || this == UNREADABLE;
	}
}
