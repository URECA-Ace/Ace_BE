package com.ace.consistency.recovery.enums;

/**
 * 관리자가 위반 결과에 대해 선택하는 복구 방식.
 * 체크마다 필요한 액션의 의미가 다르므로, 새로운 체크에 복구 정책이 추가될 때마다
 * 그 체크 전용 액션 상수를 이곳에 추가한다. 값 자체를 어떻게 해석할지(무엇을 어떻게 복구할지)는
 * 각 ConsistencyRecoveryPolicy 구현체의 책임이다.
 */
public enum RecoveryAction {

	/** 별도의 세부 방식 선택 없이 정책이 유일한 복구 절차만 수행하는 경우 사용하는 기본 액션. */
	DEFAULT,

	/**
	 * StockConsistencyCheck 전용: coupon_event에 캐시된 재고 카운터(issued_quantity/remaining_stock)가
	 * 실제 coupon_issue 활성 발급 건수와 어긋난 경우(카운터 표류), 실제 건수에 맞춰 재계산한다.
	 * actual_active_count가 total_stock을 초과한 상태(초과발급)에서는 사용할 수 없다 —
	 * 이 경우 먼저 STOCK_REVOKE_EXCESS_ISSUANCE로 초과분을 회수해야 한다.
	 */
	STOCK_RECONCILE_COUNTER,

	/**
	 * StockConsistencyCheck 전용: 실제 활성 발급 건수가 total_stock을 초과한 진짜 초과발급 상태에서,
	 * 가장 최근에 발급된 ISSUED 건부터 초과분만큼 CANCELED로 되돌려 슬롯을 반납한다.
	 */
	STOCK_REVOKE_EXCESS_ISSUANCE
}
