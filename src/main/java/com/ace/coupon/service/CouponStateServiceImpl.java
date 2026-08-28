package com.ace.coupon.service;

import java.util.Locale;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.response.CouponIssueLookupResponse;
import com.ace.coupon.dto.response.CouponStateChangeResponse;
import com.ace.coupon.entity.CouponIssue;
import com.ace.coupon.entity.CouponStateIdempotency;
import com.ace.coupon.enums.CouponIssueStatus;
import com.ace.coupon.repository.CouponIssueRepository;
import com.ace.coupon.repository.CouponStateIdempotencyRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponStateServiceImpl implements CouponStateService {

	private static final String IDEMPOTENCY_CONSTRAINT = "uk_idempotency_event_uid";

	private final CouponStateProcessor processor;
	private final CouponStateIdempotencyRepository idempotencyRepository;
	private final CouponIssueRepository couponIssueRepository;

	@Override
	public CouponIssueLookupResponse findIssue(Long eventId, Long userId) {
		CouponIssue issue = couponIssueRepository.findByCouponEvent_IdAndUser_Id(eventId, userId)
				.orElseThrow(() -> new CouponException(ErrorCode.ISSUE_NOT_FOUND));
		return new CouponIssueLookupResponse(issue.getId(), eventId, userId);
	}

	@Override
	public CouponStateChangeResponse use(Long issueId, Long userId, UUID idempotencyKey, String reason) {
		return executeWithIdempotency(issueId, userId, idempotencyKey, CouponIssueStatus.USED, reason);
	}

	@Override
	public CouponStateChangeResponse cancel(Long issueId, Long userId, UUID idempotencyKey, String reason) {
		return executeWithIdempotency(issueId, userId, idempotencyKey, CouponIssueStatus.ISSUED, reason);
	}

	@Override
	public CouponStateChangeResponse expire(Long issueId, Long userId, UUID idempotencyKey, String reason) {
		return executeWithIdempotency(issueId, userId, idempotencyKey, CouponIssueStatus.EXPIRED, reason);
	}

	private CouponStateChangeResponse executeWithIdempotency(
			Long issueId, Long userId, UUID idempotencyKey,
			CouponIssueStatus targetStatus, String reason) {
		try {
			return processor.processStateChange(issueId, userId, idempotencyKey, targetStatus, reason);
		} catch (DataIntegrityViolationException e) {
			if (isIdempotencyConstraint(e)) {
				return handleIdempotencyCollision(issueId, userId, idempotencyKey, targetStatus);
			}
			throw e;
		}
	}

	private CouponStateChangeResponse handleIdempotencyCollision(
			Long issueId, Long userId, UUID idempotencyKey,
			CouponIssueStatus targetStatus) {

		log.info("[IDEMPOTENCY] 동시 요청/재시도 충돌 감지. eventUid={}", idempotencyKey);

		CouponStateIdempotency existing = idempotencyRepository
				.findByEventUid(idempotencyKey.toString())
				.orElseThrow(() -> new CouponException(
						ErrorCode.INTERNAL_ERROR, "충돌이 발생했으나 기존 멱등 레코드를 찾을 수 없습니다."));

		boolean sameFingerprint = existing.getIssueId().equals(issueId)
				&& existing.getUserId().equals(userId)
				&& existing.getTargetStatus() == targetStatus;

		if (!sameFingerprint) {
			log.warn("[IDEMPOTENCY] Fingerprint 불일치. eventUid={}", idempotencyKey);
			throw new CouponException(ErrorCode.IDEMPOTENCY_CONFLICT);
		}

		if (existing.getOccurredAt() == null) {
			throw new CouponException(
					ErrorCode.DUPLICATE_REQUEST, "동일한 요청이 현재 처리 중입니다.");
		}

		return new CouponStateChangeResponse(
				idempotencyKey,
				existing.getIssueId(),
				existing.getEventId(),
				userId,
				existing.getFromStatus(),
				existing.getTargetStatus(),
				existing.getOccurredAt());
	}

	private boolean isIdempotencyConstraint(DataIntegrityViolationException e) {
		Throwable current = e;
		while (current != null) {
			if (current instanceof ConstraintViolationException cve
					&& cve.getConstraintName() != null
					&& cve.getConstraintName().toLowerCase(Locale.ROOT).contains(IDEMPOTENCY_CONSTRAINT)) {
				return true;
			}
			String message = current.getMessage();
			if (message != null && message.toLowerCase(Locale.ROOT).contains(IDEMPOTENCY_CONSTRAINT)) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}
}
