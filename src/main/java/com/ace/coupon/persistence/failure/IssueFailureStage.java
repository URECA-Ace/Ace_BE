package com.ace.coupon.persistence.failure;

// 발급 저장 실패 단계.
public enum IssueFailureStage {

	// MySQL 저장 실패
	DB_INSERT,

	// Stream 소비 중 실패(XACK 하지 않아 재처리 대상)
	RELAY,

	// 보상 호출 자체가 실패(재고가 차감된 채 남음)
	COMPENSATE,

	// 저장 성공 후 확정 처리만 실패(조회 상태만 어긋남)
	CONFIRM
}
