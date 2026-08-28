package com.ace.coupon.service;

// 보상(재고 원복) 실패 한 건을 재시도한 결과
// 회수 성공과 실패를 뭉개면 "되살릴 게 없음" 과 "되살리지 못함" 이 똑같이 보인다
public enum CompensationFailureRetryResult {

	// 재시도로 재고가 돌아옴
	RESOLVED,

	// 이미 돌아와 있었음
	ALREADY_RESOLVED,

	// 저장이 확인돼 원복 대신 확정으로 해소함(되돌리면 초과 발급이 된다)
	SKIPPED_PERSISTED,

	// 요청 레코드가 사라져 재고를 되돌릴 수 없음(사람이 봐야 함)
	EXPIRED,

	// 원복 대상이 아니거나 손상됨(사람이 봐야 함)
	NOT_RETRYABLE,

	// 아직 실패(다음 주기에 다시 시도)
	RETRY_FAILED;

	// pendingQuantity 가 0 으로 수렴할 수 있게 됐는가?
	// 재고를 되돌렸거나(RESOLVED), 저장된 건을 확정했거나(SKIPPED_PERSISTED)
	public boolean isRecovered() {
		return this == RESOLVED || this == ALREADY_RESOLVED || this == SKIPPED_PERSISTED;
	}

	// 자동으로는 더 손쓸 수 없어 사람 확인이 필요한 경우
	public boolean needsAttention() {
		return this == EXPIRED || this == NOT_RETRYABLE;
	}
}
