package com.ace.coupon.service;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.response.CouponStateChangeResponse;
import com.ace.coupon.entity.CouponHistory;
import com.ace.coupon.entity.CouponIssue;
import com.ace.coupon.enums.CouponIssueStatus;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.repository.CouponHistoryRepository;
import com.ace.coupon.repository.CouponIssueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponStateServiceImpl implements CouponStateService {

	private final CouponIssueRepository couponIssueRepository;
	private final CouponHistoryRepository couponHistoryRepository;
	private final CouponIssueRedisProperties properties;

	@Override
	@Transactional 
	public CouponStateChangeResponse use(Long issueId, Long userId, UUID idempotencyKey, String reason) {
		return changeState(issueId, userId, idempotencyKey, CouponIssueStatus.USED, reason);
	}
 
	@Override
	@Transactional
	public CouponStateChangeResponse cancel(Long issueId, Long userId, UUID idempotencyKey, String reason) {

		return changeState(issueId, userId, idempotencyKey, CouponIssueStatus.ISSUED, reason);

	}

	private CouponStateChangeResponse changeState(Long issueId, Long userId, UUID idempotencyKey,
			CouponIssueStatus targetStatus, String reason) {

		String eventUid = idempotencyKey.toString();

		// 멱등성 검사
		Optional<CouponHistory> existingOpt = couponHistoryRepository.findByEventUid(eventUid);
		if (existingOpt.isPresent()) {
			return restoreIdempotentResponse(existingOpt.get(), issueId, userId, targetStatus, idempotencyKey);
		}

		CouponIssue issue = couponIssueRepository.findByIdForUpdate(issueId)
				.orElseThrow(() -> new CouponException(ErrorCode.ISSUE_NOT_FOUND));

		if (!issue.getUser().getId().equals(userId)) {
			throw new CouponException(ErrorCode.INVALID_REQUEST, "본인의 쿠폰만 상태를 변경할 수 있습니다.");
		}

		ZoneId zoneId = properties.zoneId();
		LocalDateTime now = LocalDateTime.now(zoneId);

		validateValidityPeriod(issue, targetStatus, now);

		CouponIssueStatus previousStatus = issue.getStatus();
		applyStateTransition(issue, targetStatus, now);

		String defaultReason = (targetStatus == CouponIssueStatus.ISSUED) ? "USE_CANCELED" : "USED";
		CouponHistory history = CouponHistory.builder()
				.couponIssue(issue)
				.fromStatus(previousStatus)
				.toStatus(targetStatus)
				.actor("USER_" + userId)
				.reason(reason != null ? reason : defaultReason)
				.occurredAt(now)
				.recordedAt(now)
				.eventUid(eventUid)
				.build();
		couponHistoryRepository.save(history);

		return new CouponStateChangeResponse(
				idempotencyKey,
				issue.getId(),
				issue.getCouponEvent().getId(),
				userId,
				previousStatus,
				targetStatus,
				now);
	}


	private CouponStateChangeResponse restoreIdempotentResponse(
			CouponHistory existing, Long issueId, Long userId,
			CouponIssueStatus targetStatus, UUID idempotencyKey) {
		
		boolean sameFingerprint = existing.getCouponIssue().getId().equals(issueId)
				&& existing.getActor().equals("USER_" + userId)
				&& existing.getToStatus() == targetStatus;
		
		if (!sameFingerprint) {
			
			throw new CouponException(ErrorCode.IDEMPOTENCY_CONFLICT);
		}
		
		return new CouponStateChangeResponse(
				idempotencyKey,
				existing.getCouponIssue().getId(),
				existing.getCouponIssue().getCouponEvent().getId(),
				userId,
				existing.getFromStatus(),
				existing.getToStatus(),
				existing.getOccurredAt());
	}

	private void validateValidityPeriod(CouponIssue issue, CouponIssueStatus targetStatus, LocalDateTime now) {
		if (targetStatus == CouponIssueStatus.USED) {
			if (issue.getValidFrom().isAfter(now)) {
				throw new CouponException(ErrorCode.EVENT_NOT_OPEN, "쿠폰 사용 시작 전입니다."); 
			}
			if (issue.getValidTo().isBefore(now)) {
				throw new CouponException(ErrorCode.ALREADY_EXPIRED);
			}
		} else if (targetStatus == CouponIssueStatus.ISSUED) {
			if (issue.getValidTo().isBefore(now)) {
				throw new CouponException(ErrorCode.ALREADY_EXPIRED, "만료된 쿠폰은 취소할 수 없습니다.");
			}
		}
	}
	
	private void applyStateTransition(CouponIssue issue, CouponIssueStatus targetStatus, LocalDateTime now) {
		try {
			if (targetStatus == CouponIssueStatus.USED) {
				
				issue.use(now);
				
			} else if (targetStatus == CouponIssueStatus.ISSUED) {
				
				issue.cancel(now);
			}
			
		} catch (IllegalStateException e) {
			if (targetStatus == CouponIssueStatus.USED && issue.getStatus() == CouponIssueStatus.USED) {
				
				throw new CouponException(ErrorCode.ALREADY_USED);
			}
			if (targetStatus == CouponIssueStatus.ISSUED && issue.getStatus() == CouponIssueStatus.ISSUED) {
				
				throw new CouponException(ErrorCode.NOT_YET_USED);
			}
			
			
			throw new CouponException(ErrorCode.INVALID_STATE_TRANSITION);
		}
	}

}


