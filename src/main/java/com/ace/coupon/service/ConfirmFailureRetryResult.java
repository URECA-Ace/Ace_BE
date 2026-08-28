package com.ace.coupon.service;

// 확정 실패 한 건을 재확인한 결과
// 회수 성공과 실패를 뭉개면 Redis 장애가 회수할 게 없음과 똑같이 보인다
public enum ConfirmFailureRetryResult {

	// 재확인으로 확정됨
	RESOLVED,

	// 이미 확정돼 있었음(회수된 것)
	ALREADY_RESOLVED,

	// 요청 레코드가 사라져 되돌릴 수 없음(사람이 봐야 함)
	EXPIRED,

	// 확정 대상이 아니거나 손상됨(사람이 봐야 함)
	NOT_RETRYABLE,

	// 아직 실패(다음 주기에 다시 시도)
	RETRY_FAILED;

	// 확정 카운터가 올라갔는가?
	// pendingQuantity 가 줄어드는 경우
	public boolean isRecovered() {
		return this == RESOLVED || this == ALREADY_RESOLVED;
	}

	// 자동으로는 더 손쓸 수 없어 사람 확인이 필요한 경우
	public boolean needsAttention() {
		return this == EXPIRED || this == NOT_RETRYABLE;
	}
}
