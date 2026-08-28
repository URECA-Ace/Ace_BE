package com.ace.coupon.service;

import java.util.List;

import org.springframework.stereotype.Component;

import com.ace.coupon.entity.IssueFailureLog;
import com.ace.coupon.enums.IssueFailureAction;
import com.ace.coupon.persistence.failure.IssueFailureStatus;

// 실패 한 건에 허용되는 조치를 정한다
// 화면이 액션을 지어내지 않도록 서버가 목록을 내려주고, 실행 시 같은 규칙으로 다시 검사한다
@Component
public class IssueFailureActionPolicy {

	public List<IssueFailureAction> availableActions(IssueFailureLog failure) {
		IssueFailureStatus status = statusOf(failure);

		if (status == IssueFailureStatus.SETTLED) {
			return List.of();
		}
		if (status == IssueFailureStatus.RETRYABLE) {
			return List.of(IssueFailureAction.RETRY);
		}
		// 회수 불가 판정이라도 한 번은 직접 시도해 봐야 종결할 수 있다
		return failure.getAttemptCount() > 0
				? List.of(IssueFailureAction.RETRY, IssueFailureAction.RESOLVE)
				: List.of(IssueFailureAction.RETRY);
	}

	public boolean allows(IssueFailureLog failure, IssueFailureAction action) {
		return availableActions(failure).contains(action);
	}

	public IssueFailureStatus statusOf(IssueFailureLog failure) {
		return IssueFailureStatus.of(
				failure.getFailureStage(), failure.getCompensationResult(), failure.isResolved());
	}
}
