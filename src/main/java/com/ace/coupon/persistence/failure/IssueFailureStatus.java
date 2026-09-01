package com.ace.coupon.persistence.failure;

import lombok.Getter;

// 실패 한 건의 처리 상태
// resolved_at 은 보상에 성공해도 채워지지 않으므로 단독 판정 기준이 될 수 없다
@Getter
public enum IssueFailureStatus {

	// 재고가 돌아왔거나 확정이 끝나 더 볼 것이 없는 건
	SETTLED("종결"),

	// 자동 재처리기가 다음 주기에 다시 집는 건
	RETRYABLE("자동 재시도 대기"),

	// 자동으로는 더 손쓸 수 없어 사람이 봐야 하는 건
	UNRECOVERABLE("확인 필요");

	private final String label;

	IssueFailureStatus(String label) {
		this.label = label;
	}

	public static IssueFailureStatus of(
			IssueFailureStage stage, String compensationResult, boolean resolved) {
		if (resolved) {
			return SETTLED;
		}
		IssueFailureStageGroup group = IssueFailureStageGroup.of(stage);
		if (compensationResult == null) {
			return UNRECOVERABLE;
		}
		if (group.getSettledResults().contains(compensationResult)) {
			return SETTLED;
		}
		return group.getRetryableResults().contains(compensationResult)
				? RETRYABLE
				: UNRECOVERABLE;
	}
}
