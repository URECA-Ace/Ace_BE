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

		// 멱등성 검사
		String eventUid = idempotencyKey.toString();
		if (couponHistoryRepository.existsByEventUid(eventUid)) { 
			throw new CouponException(ErrorCode.DUPLICATE_REQUEST);
		}

		CouponIssue issue = couponIssueRepository.findByIdForUpdate(issueId)
				.orElseThrow(() -> new CouponException(ErrorCode.ISSUE_NOT_FOUND)); 

		if (!issue.getUser().getId().equals(userId)) {

			throw new CouponException(ErrorCode.INVALID_REQUEST, "본인의 쿠폰만 상태를 변경할 수 있습니다."); 
		}

		ZoneId zoneId = properties.zoneId();
		LocalDateTime now = LocalDateTime.now(zoneId); 

		if (targetStatus == CouponIssueStatus.USED && issue.getValidTo().isBefore(now)) {
			throw new CouponException(ErrorCode.ALREADY_EXPIRED); 
		}

		CouponIssueStatus previousStatus = issue.getStatus();

		try {
			if (targetStatus == CouponIssueStatus.USED) { 
				issue.use(now);
				
			} else if (targetStatus == CouponIssueStatus.ISSUED) {
				issue.cancel(now);
			} 
			
			
		} catch (IllegalStateException e)  {
			
			if (targetStatus == CouponIssueStatus.USED && issue.getStatus() == CouponIssueStatus.USED) {
				throw new CouponException(ErrorCode.ALREADY_USED);
			}
			
			if (targetStatus == CouponIssueStatus.ISSUED && issue.getStatus() == CouponIssueStatus.ISSUED) {
				throw new CouponException(ErrorCode.NOT_YET_USED);
			}
			
			throw new CouponException(ErrorCode.INVALID_STATE_TRANSITION);
		}
		
		
		
		CouponHistory history = CouponHistory.builder().couponIssue(issue).fromStatus(previousStatus)
				.toStatus(targetStatus).actor("USER_" + userId)
				.reason(reason != null ? reason : (targetStatus == CouponIssueStatus.ISSUED ? "USE_CANCELED" : "USED"))
				.occurredAt(now).recordedAt(now).eventUid(eventUid).build();

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

}
