package com.ace.coupon.persistence;

import org.springframework.stereotype.Component;

import com.ace.coupon.persistence.failure.IssueFailure;
import com.ace.coupon.persistence.failure.IssueFailureRecorder;
import com.ace.coupon.persistence.failure.IssueFailureStage;
import com.ace.coupon.redis.CouponIssueCompensationResult;
import com.ace.coupon.redis.RedisCouponIssueProcessor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 저장 + 실패 시 원복 + 실패 기록
@Slf4j
@Component
@RequiredArgsConstructor
public class IssuePersistenceCoordinator {

	private static final String COMPENSATION_CALL_FAILED = "CALL_FAILED";
	private static final String COMPENSATION_SKIPPED_PERSISTED = "SKIPPED_PERSISTED";
	private static final String COMPENSATION_SKIPPED_UNVERIFIED = "SKIPPED_UNVERIFIED";

	private final IssuePersistenceService persistenceService;
	private final IssuePersistenceProbe persistenceProbe;
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
	// 반환값이 null 이면 원복 여부가 불확실하다는 뜻이라 호출부가 ACK 를 미뤄야 한다
	public CouponIssueCompensationResult abandon(
			IssueRecord record,
			IssueFailureStage stage,
			String incidentId,
			RuntimeException cause) {
		return compensate(record, stage, incidentId, cause);
	}

	// 재고와 중복 표시를 되돌리고 흔적을 남긴다
	// 저장이 이미 됐거나 판별할 수 없으면 되돌리지 않는다
	// 원복을 빠뜨리면 재고 1장이 묶이지만, 잘못 되돌리면 초과 발급이 된다
	private CouponIssueCompensationResult compensate(
			IssueRecord record,
			IssueFailureStage stage,
			String incidentId,
			RuntimeException cause) {

		IssuePersistenceProbe.Result probed = persistenceProbe.probe(record);
		if (probed != IssuePersistenceProbe.Result.ABSENT) {
			return skipCompensation(record, stage, incidentId, cause, probed);
		}

		String compensationResult;
		CouponIssueCompensationResult result;
		try {
			result = issueProcessor.compensate(
					record.campaignId(), record.userId(), record.requestId());
			compensationResult = result.name();
		} catch (RuntimeException compensationFailure) {
			log.error("보상 실패 - 재고가 복구되지 않았습니다: requestId={}, incidentId={}",
					record.requestId(), incidentId, compensationFailure);
			failureRecorder.record(IssueFailure.of(
					record,
					IssueFailureStage.COMPENSATE,
					COMPENSATION_CALL_FAILED,
					summary(compensationFailure),
					incidentId));
			result = null;
			compensationResult = COMPENSATION_CALL_FAILED;
		}

		failureRecorder.record(IssueFailure.of(
				record, stage, compensationResult, summary(cause), incidentId));
		return result;
	}

	// 저장이 확인됐거나 판별 불가면 원복을 건너뛴다
	// 판별 불가는 정합성 복구 대상으로 남긴다
	private CouponIssueCompensationResult skipCompensation(
			IssueRecord record,
			IssueFailureStage stage,
			String incidentId,
			RuntimeException cause,
			IssuePersistenceProbe.Result probed) {

		boolean persisted = probed == IssuePersistenceProbe.Result.PERSISTED;
		log.warn("원복을 건너뜁니다: requestId={}, probe={}, incidentId={}",
				record.requestId(), probed, incidentId);

		failureRecorder.record(IssueFailure.of(
				record,
				stage,
				persisted ? COMPENSATION_SKIPPED_PERSISTED : COMPENSATION_SKIPPED_UNVERIFIED,
				summary(cause),
				incidentId));

		// 저장이 확인된 건은 재처리할 필요가 없어 확정으로 본다
		return persisted ? CouponIssueCompensationResult.NOT_COMPENSABLE : null;
	}

	private String summary(Throwable throwable) {
		return throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
	}
}
