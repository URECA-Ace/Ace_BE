package com.ace.coupon.service;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.response.CouponStateChangeResponse;
import com.ace.coupon.entity.CouponStateIdempotency;
import com.ace.coupon.enums.CouponIssueStatus;
import com.ace.coupon.repository.CouponStateIdempotencyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponStateServiceImpl implements CouponStateService {

	private final CouponStateProcessor processor;
	private final CouponStateIdempotencyRepository idempotencyRepository;

	@Override
	public CouponStateChangeResponse use(Long issueId, Long userId, UUID idempotencyKey, String reason) {
		return executeWithIdempotency(issueId, userId, idempotencyKey, CouponIssueStatus.USED, reason);
	}

	@Override
	public CouponStateChangeResponse cancel(Long issueId, Long userId, UUID idempotencyKey, String reason) {
		return executeWithIdempotency(issueId, userId, idempotencyKey, CouponIssueStatus.ISSUED, reason);
	}

	private CouponStateChangeResponse executeWithIdempotency(
			Long issueId, Long userId, UUID idempotencyKey,
			CouponIssueStatus targetStatus, String reason) {
		try {
			return processor.processStateChange(issueId, userId, idempotencyKey, targetStatus, reason);
		} catch (DataIntegrityViolationException e) {
			return handleIdempotencyCollision(issueId, userId, idempotencyKey, targetStatus);
		}
	}

	@Transactional(readOnly = true)
	protected CouponStateChangeResponse handleIdempotencyCollision(
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
}
