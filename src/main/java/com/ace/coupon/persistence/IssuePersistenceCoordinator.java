package com.ace.coupon.persistence;

import org.springframework.stereotype.Component;

import com.ace.coupon.redis.RedisCouponIssueProcessor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.ace.coupon.persistence.failure.IssueFailure;
import com.ace.coupon.persistence.failure.IssueFailureRecorder;
import com.ace.coupon.persistence.failure.IssueFailureStage;

// 저장 + 실패 시 원복 + 실패 기록
@Slf4j
@Component
@RequiredArgsConstructor
public class IssuePersistenceCoordinator {

	private static final String COMPENSATION_CALL_FAILED = "CALL_FAILED";

	private final IssuePersistenceService persistenceService;
	private final RedisCouponIssueProcessor issueProcessor;
	private final IssueFailureRecorder failureRecorder;

	// stage: 실패 시 기록할 단계
	// coupon_issue.issue_id 반환
	public long persist(IssueRecord record, IssueFailureStage stage, String incidentId) {
		try {
			return persistenceService.persist(record);
		} catch (RuntimeException failure) {
			compensate(record, stage, incidentId, failure);
			throw failure;
		}
	}

	// 저장을 포기하고 되돌림
	// RELAY 가 재시도 한도를 넘겼을 때만 사용
	public void abandon(
			IssueRecord record,
			IssueFailureStage stage,
			String incidentId,
			RuntimeException cause) {
		compensate(record, stage, incidentId, cause);
	}

	// 재고와 중복 표시를 되돌리고 흔적을 남긴다
	private void compensate(
			IssueRecord record,
			IssueFailureStage stage,
			String incidentId,
			RuntimeException cause) {

		String compensationResult;
		try {
			compensationResult = issueProcessor
					.compensate(record.campaignId(), record.userId(), record.requestId())
					.name();
		} catch (RuntimeException compensationFailure) {
			log.error("보상 실패 - 재고가 복구되지 않았습니다: requestId={}, incidentId={}",
					record.requestId(), incidentId, compensationFailure);
			failureRecorder.record(IssueFailure.of(
					record,
					IssueFailureStage.COMPENSATE,
					COMPENSATION_CALL_FAILED,
					summary(compensationFailure),
					incidentId));
			compensationResult = COMPENSATION_CALL_FAILED;
		}

		failureRecorder.record(IssueFailure.of(
				record, stage, compensationResult, summary(cause), incidentId));
	}

	private String summary(Throwable throwable) {
		return throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
	}
}
