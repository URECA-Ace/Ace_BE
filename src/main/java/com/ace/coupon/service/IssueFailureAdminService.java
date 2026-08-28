package com.ace.coupon.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.request.IssueFailureActionRequest;
import com.ace.coupon.dto.response.IssueFailureActionResultResponse;
import com.ace.coupon.dto.response.IssueFailureItemResponse;
import com.ace.coupon.entity.IssueFailureLog;
import com.ace.coupon.enums.IssueFailureAction;
import com.ace.coupon.persistence.IssuePersistenceProbe;
import com.ace.coupon.persistence.failure.IssueFailureStageGroup;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.repository.IssueFailureLogRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 운영자가 실패 한 건에 직접 조치
// 재시도는 자동 재처리기와 같은 절차를 그대로 호출하고, 종결은 사람 판단을 근거와 함께 남긴다.
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueFailureAdminService {

	private static final String UNKNOWN_OPERATOR = "unknown";

	private final IssueFailureLogRepository repository;
	private final IssueFailureActionPolicy actionPolicy;
	private final ConfirmFailureRetryService confirmRetryService;
	private final CompensationFailureRetryService compensationRetryService;
	private final IssuePersistenceProbe persistenceProbe;
	private final CouponIssueRedisProperties properties;

	@Transactional
	public IssueFailureActionResultResponse execute(
			long failureId, IssueFailureAction action, IssueFailureActionRequest request) {

		IssueFailureLog failure = repository.findById(failureId)
				.orElseThrow(() -> new CouponException(ErrorCode.ISSUE_FAILURE_NOT_FOUND));

		if (!actionPolicy.allows(failure, action)) {
			throw new CouponException(
					ErrorCode.ISSUE_FAILURE_ACTION_NOT_ALLOWED,
					"현재 상태에서 실행할 수 없는 조치입니다: " + action);
		}

		String outcome = action == IssueFailureAction.RETRY
				? retry(failure)
				: resolve(failure, request);

		return new IssueFailureActionResultResponse(
				action, outcome, IssueFailureItemResponse.from(failure));
	}

	// 자동 재처리기의 단건 경로를 그대로 쓴다. 복구 로직을 여기서 다시 만들지 않는다
	private String retry(IssueFailureLog failure) {
		IssueFailureStageGroup group = IssueFailureStageGroup.of(failure.getFailureStage());
		String outcome = group == IssueFailureStageGroup.CONFIRM
				? confirmRetryService.retry(failure).name()
				: compensationRetryService.retry(failure).name();

		log.info("발급 실패 수동 재시도: failureId={}, stage={}, outcome={}",
				failure.getId(), failure.getFailureStage(), outcome);
		return outcome;
	}

	// 닫기 전에 저장 상태를 다시 확인해 그 결과를 사유와 함께 남긴다
	private String resolve(IssueFailureLog failure, IssueFailureActionRequest request) {
		if (request == null || request.reason() == null || request.reason().isBlank()) {
			throw new CouponException(ErrorCode.ISSUE_FAILURE_REASON_REQUIRED);
		}

		IssuePersistenceProbe.Result probed = persistenceProbe.probe(
				failure.getEventId(),
				failure.getUserId(),
				failure.getRequestId(),
				failure.getIssueSequence());

		String operator = request.operator() == null || request.operator().isBlank()
				? UNKNOWN_OPERATOR
				: request.operator().trim();

		failure.resolveManually(
				LocalDateTime.now(properties.zoneId()),
				operator,
				request.reason().trim(),
				probed.name());
		repository.save(failure);

		log.warn("발급 실패를 사람이 종결했습니다: failureId={}, operator={}, probe={}, reason={}",
				failure.getId(), operator, probed, request.reason().trim());
		return probed.name();
	}
}
