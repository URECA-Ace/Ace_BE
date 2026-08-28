package com.ace.coupon.service;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.response.CouponIssueAcceptedResponse;
import com.ace.coupon.dto.response.CouponIssueStatusResponse;
import com.ace.coupon.persistence.CouponIssuePersistenceProperties;
import com.ace.coupon.persistence.failure.IssueFailureStage;
import com.ace.coupon.persistence.IssuePersistenceCoordinator;
import com.ace.coupon.persistence.IssueRecord;
import com.ace.coupon.redis.CouponIssueDecision;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.redis.CouponIssueRequestState;
import com.ace.coupon.redis.RedisCouponIssueProcessor;
import com.ace.coupon.repository.CouponEventRepository;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponIssueServiceImpl implements CouponIssueService {

	private final RedisCouponIssueProcessor issueProcessor;
	private final CouponIssueRedisProperties properties;
	private final CouponEventRepository couponEventRepository;
	private final CouponIssuePersistenceProperties persistenceProperties;
	private final IssuePersistenceCoordinator persistenceCoordinator;
	private final MeterRegistry meterRegistry;

	@Override
	public CouponIssueAcceptedResponse issue(Long eventId, Long userId, UUID idempotencyKey) {
		try {
			CouponIssueAcceptedResponse response = doIssue(eventId, userId, idempotencyKey);
			meterRegistry.counter("coupon.issue", "result", "success").increment();
			return response;
		} catch (CouponException exception) {
			meterRegistry.counter("coupon.issue",
					"result", "fail",
					"reason", exception.getErrorCode().name()).increment();
			throw exception;
		}
	}

	private CouponIssueAcceptedResponse doIssue(Long eventId, Long userId, UUID idempotencyKey) {
		CouponIssueDecision decision;
		try {
			decision = issueProcessor.issue(eventId, userId, idempotencyKey);
		} catch (DataAccessException | IllegalStateException exception) {
			throw new CouponException(
					ErrorCode.ISSUE_TEMPORARILY_UNAVAILABLE,
					ErrorCode.ISSUE_TEMPORARILY_UNAVAILABLE.getDefaultMessage(),
					exception);
		}

		return switch (decision.code()) {
			case ACCEPTED -> {
				// RELAY 는 Stream 소비자가 저장한다. 여기서 저장하면 같은 건이 두 번 처리된다
				if (!persistenceProperties.relay()) {
					persist(eventId, userId, idempotencyKey, decision);
				}
				yield CouponIssueAcceptedResponse.accepted(
						idempotencyKey,
						eventId,
						userId,
						decision.issueSequence(),
						decision.remainingStock(),
						OffsetDateTime.ofInstant(decision.decidedAt(), properties.zoneId()));
			}
			case SOLD_OUT -> throw new CouponException(ErrorCode.SOLD_OUT);
			case ALREADY_ISSUED -> throw new CouponException(ErrorCode.ALREADY_ISSUED);
			case EVENT_NOT_OPEN -> throw new CouponException(ErrorCode.EVENT_NOT_OPEN);
			case EVENT_CLOSED -> throw new CouponException(ErrorCode.EVENT_CLOSED);
			case IDEMPOTENCY_CONFLICT -> throw new CouponException(ErrorCode.IDEMPOTENCY_CONFLICT);
			case PERSISTENCE_FAILED -> throw new CouponException(ErrorCode.ISSUE_PERSIST_FAILED);
			case CAMPAIGN_NOT_INITIALIZED -> throw missingCampaignOrUnavailable(eventId);
			case CORRUPTED_STATE, INVALID_ARGUMENT, INTERNAL_WRITE_ERROR ->
					throw new CouponException(ErrorCode.ISSUE_TEMPORARILY_UNAVAILABLE);
		};
	}

	private CouponException missingCampaignOrUnavailable(Long eventId) {
		try {
			if (!couponEventRepository.existsById(eventId)) {
				return new CouponException(ErrorCode.EVENT_NOT_FOUND);
			}
		} catch (DataAccessException exception) {
			return new CouponException(
					ErrorCode.ISSUE_TEMPORARILY_UNAVAILABLE,
					ErrorCode.ISSUE_TEMPORARILY_UNAVAILABLE.getDefaultMessage(),
					exception);
		}
		return new CouponException(ErrorCode.ISSUE_TEMPORARILY_UNAVAILABLE);
	}

	/**
	 * SYNC 경로 — 응답 전에 MySQL 저장까지 끝낸다.
	 *
	 * <p>실패하면 이미 차감된 Redis 재고를 되돌리고 실패를 기록한 뒤 500 으로 응답한다.
	 * 여기서 202 를 돌려주면 사용자는 발급됐다고 알지만 이력이 없다.
	 *
	 * <p>저장 성공 후 요청 상태를 확정으로 올리는 처리(P3)는 아직 없다. 그래서 저장이 끝나도
	 * 상태 조회는 {@code ACCEPTED} 로 보인다. 데이터 정합성에는 영향이 없다 — MySQL 이 확정 소스다.
	 */
	private void persist(Long eventId, Long userId, UUID requestId, CouponIssueDecision decision) {
		// 실패 기록과 응답이 같은 값을 갖도록 여기서 발급한다
		String incidentId = UUID.randomUUID().toString();
		IssueRecord record = IssueRecord.fromDecision(eventId, userId, requestId, decision);

		try {
			persistenceCoordinator.persist(record, IssueFailureStage.DB_INSERT, incidentId);
		} catch (RuntimeException exception) {
			throw new CouponException(
					ErrorCode.ISSUE_PERSIST_FAILED,
					ErrorCode.ISSUE_PERSIST_FAILED.getDefaultMessage(),
					exception,
					incidentId);
		}
	}

	@Override
	public CouponIssueStatusResponse findStatus(Long eventId, UUID requestId) {
		CouponIssueRequestState state;
		try {
			state = issueProcessor.findRequest(eventId, requestId);
		} catch (DataAccessException | IllegalStateException exception) {
			throw new CouponException(
					ErrorCode.ISSUE_TEMPORARILY_UNAVAILABLE,
					ErrorCode.ISSUE_TEMPORARILY_UNAVAILABLE.getDefaultMessage(),
					exception);
		}

		if (state == null) {
			throw new CouponException(ErrorCode.ISSUE_NOT_FOUND);
		}

		return new CouponIssueStatusResponse(
				state.requestId(),
				state.campaignId(),
				state.userId(),
				state.issueSequence(),
				state.remainingStock(),
				state.status(),
				OffsetDateTime.ofInstant(state.decidedAt(), properties.zoneId()));
	}
}
