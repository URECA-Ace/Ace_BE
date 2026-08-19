package com.ace.coupon.service;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.response.CouponIssueAcceptedResponse;
import com.ace.coupon.dto.response.CouponIssueStatusResponse;
import com.ace.coupon.redis.CouponIssueDecision;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.redis.CouponIssueRequestState;
import com.ace.coupon.redis.RedisCouponIssueProcessor;
import com.ace.coupon.repository.CouponEventRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponIssueServiceImpl implements CouponIssueService {

	private final RedisCouponIssueProcessor issueProcessor;
	private final CouponIssueRedisProperties properties;
	private final CouponEventRepository couponEventRepository;

	@Override
	public CouponIssueAcceptedResponse issue(Long eventId, Long userId, UUID idempotencyKey) {
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
			case ACCEPTED -> CouponIssueAcceptedResponse.accepted(
					idempotencyKey,
					eventId,
					userId,
					decision.issueSequence(),
					decision.remainingStock(),
					OffsetDateTime.ofInstant(decision.decidedAt(), properties.zoneId()));
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
