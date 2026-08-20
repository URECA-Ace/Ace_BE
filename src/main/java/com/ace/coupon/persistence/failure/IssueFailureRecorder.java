package com.ace.coupon.persistence.failure;

// 발급 저장, 보상 실패 기록
public interface IssueFailureRecorder {

	void record(IssueFailure failure);
}
