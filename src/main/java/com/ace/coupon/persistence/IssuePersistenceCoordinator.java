package com.ace.coupon.persistence;

import org.springframework.stereotype.Component;

import com.ace.coupon.persistence.failure.IssueFailure;
import com.ace.coupon.persistence.failure.IssueFailureRecorder;
import com.ace.coupon.persistence.failure.IssueFailureStage;
import com.ace.coupon.redis.CouponIssueCompensationResult;
import com.ace.coupon.redis.CouponIssueConfirmResult;
import com.ace.coupon.redis.RedisCouponIssueProcessor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 저장 + 실패 시 원복 + 실패 기록
@Slf4j
@Component
@RequiredArgsConstructor
public class IssuePersistenceCoordinator {

	// 확정 호출 자체가 예외로 끝난 경우
	public static final String CALL_FAILED = "CALL_FAILED";
	private static final String COMPENSATION_SKIPPED_PERSISTED = "SKIPPED_PERSISTED";
	private static final String COMPENSATION_SKIPPED_UNVERIFIED = "SKIPPED_UNVERIFIED";

	private final IssuePersistenceService persistenceService;
	private final IssuePersistenceProbe persistenceProbe;
	private final RedisCouponIssueProcessor issueProcessor;
	private final IssueFailureRecorder failureRecorder;

	// stage: 실패 시 기록할 단계
	// coupon_issue.issue_id 반환
	public long persist(IssueRecord record, IssueFailureStage stage, String incidentId) {
		long issueId;
		try {
			issueId = persistenceService.persist(record);
		} catch (RuntimeException failure) {
			compensate(record, stage, incidentId, failure);
			throw failure;
		}
		confirmQuietly(record, incidentId);
		return issueId;
	}

	// 저장이 커밋된 뒤 요청 상태를 CONFIRMED(RELAY 전용)
	// 보상은 하지 않지만 재시도는 함
	// 재시도해도 결과가 같은 거절은 그대로 삼킴 안 그러면 엔트리가 Stream 에서 빠지지 X
	public void confirmPersisted(IssueRecord record, String incidentId) {
		CouponIssueConfirmResult result;
		try {
			result = issueProcessor.confirm(
					record.campaignId(), record.userId(), record.requestId());
		} catch (RuntimeException confirmFailure) {
			log.warn("확정 호출 실패, 재처리 대상입니다: requestId={}, incidentId={}",
					record.requestId(), incidentId, confirmFailure);
			recordConfirmFailure(record, incidentId, CALL_FAILED, summary(confirmFailure));
			throw confirmFailure;
		}

		if (isConfirmed(result)) {
			return;
		}

		recordRejectedConfirm(record, incidentId, result);
		if (result == CouponIssueConfirmResult.INTERNAL_WRITE_ERROR) {
			// 예외는 아니지만 Redis 쓰기 실패라 재시도하면 성공할 수 있다
			throw new IllegalStateException(
					"발급 확정 쓰기에 실패했습니다: requestId=" + record.requestId());
		}
	}

	// 커밋이 끝난 뒤에만 호출(SYNC 전용)
	// 예외를 밖으로 던지지 않는다. 저장은 이미 커밋됐는데 요청 스레드가 500 을 응답하게 된다
	private void confirmQuietly(IssueRecord record, String incidentId) {
		try {
			CouponIssueConfirmResult result = issueProcessor.confirm(
					record.campaignId(), record.userId(), record.requestId());
			if (isConfirmed(result)) {
				return;
			}
			recordRejectedConfirm(record, incidentId, result);
		} catch (RuntimeException confirmFailure) {
			log.warn("확정 처리 실패 - 상태 조회만 어긋납니다: requestId={}, incidentId={}",
					record.requestId(), incidentId, confirmFailure);
			recordConfirmFailure(record, incidentId, CALL_FAILED, summary(confirmFailure));
		}
	}

	private boolean isConfirmed(CouponIssueConfirmResult result) {
		return result == CouponIssueConfirmResult.CONFIRMED_NOW
				|| result == CouponIssueConfirmResult.ALREADY_CONFIRMED;
	}

	private void recordRejectedConfirm(
			IssueRecord record, String incidentId, CouponIssueConfirmResult result) {
		log.warn("확정되지 않았습니다: requestId={}, result={}, incidentId={}",
				record.requestId(), result, incidentId);
		recordConfirmFailure(record, incidentId, String.valueOf(result), "확정 거절: " + result);
	}

	// 여기서 예외가 나가면 RELAY 가 저장된 건을 무한 재처리
	private void recordConfirmFailure(
			IssueRecord record, String incidentId, String confirmResult, String detail) {
		try {
			failureRecorder.record(IssueFailure.of(
					record, IssueFailureStage.CONFIRM, confirmResult, detail, incidentId));
		} catch (RuntimeException recordFailure) {
			log.error("확정 실패 기록에 실패했습니다: requestId={}, incidentId={}",
					record.requestId(), incidentId, recordFailure);
		}
	}

	// 확정을 포기하고 흔적만(RELAY 전용)
	// 저장이 이미 커밋된 상태라 보상 경로를 타면 안 된다
	// probe 는 같은 requestId 의 다른 발급을 ABSENT 로 판정하므로, 그 경로에 들어가면
	// MySQL 에 행이 있는 채로 재고가 복구돼 초과 발급이 된다
	public void recordConfirmAbandoned(
			IssueRecord record, String incidentId, RuntimeException cause) {
		recordConfirmFailure(record, incidentId, CALL_FAILED, summary(cause));
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
					CALL_FAILED,
					summary(compensationFailure),
					incidentId));
			result = null;
			compensationResult = CALL_FAILED;
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
