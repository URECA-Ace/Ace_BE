package com.ace.coupon.persistence.failure;

import java.util.Set;

import com.ace.coupon.persistence.IssuePersistenceCoordinator;
import com.ace.coupon.redis.CouponIssueCompensationResult;
import com.ace.coupon.redis.CouponIssueConfirmResult;

import lombok.Getter;

// 실패 단계를 재처리기 단위로 묶은 것
// 그룹마다 재시도 가능/종결 판정에 쓰는 compensation_result 값이 다르므로 합산하면 틀린 수가 나온다
@Getter
public enum IssueFailureStageGroup {

	PERSIST(
			"저장 실패",
			Set.of(
					IssueFailureStage.DB_INSERT,
					IssueFailureStage.RELAY,
					IssueFailureStage.COMPENSATE),
			Set.of(
					IssuePersistenceCoordinator.CALL_FAILED,
					IssuePersistenceCoordinator.COMPENSATION_SKIPPED_UNVERIFIED,
					IssuePersistenceCoordinator.COMPENSATION_SKIPPED_PERSISTED,
					CouponIssueCompensationResult.INTERNAL_WRITE_ERROR.name()),
			Set.of(
					CouponIssueCompensationResult.COMPENSATED.name(),
					CouponIssueCompensationResult.ALREADY_COMPENSATED.name(),
					CouponIssueConfirmResult.CONFIRMED_NOW.name(),
					CouponIssueConfirmResult.ALREADY_CONFIRMED.name())),

	CONFIRM(
			"확정 실패",
			Set.of(IssueFailureStage.CONFIRM),
			Set.of(
					IssuePersistenceCoordinator.CALL_FAILED,
					CouponIssueConfirmResult.INTERNAL_WRITE_ERROR.name()),
			Set.of(
					CouponIssueConfirmResult.CONFIRMED_NOW.name(),
					CouponIssueConfirmResult.ALREADY_CONFIRMED.name()));

	private final String label;
	private final Set<IssueFailureStage> stages;
	private final Set<String> retryableResults;
	private final Set<String> settledResults;

	IssueFailureStageGroup(
			String label,
			Set<IssueFailureStage> stages,
			Set<String> retryableResults,
			Set<String> settledResults) {
		this.label = label;
		this.stages = stages;
		this.retryableResults = retryableResults;
		this.settledResults = settledResults;
	}

	public static IssueFailureStageGroup of(IssueFailureStage stage) {
		return stage == IssueFailureStage.CONFIRM ? CONFIRM : PERSIST;
	}
}
