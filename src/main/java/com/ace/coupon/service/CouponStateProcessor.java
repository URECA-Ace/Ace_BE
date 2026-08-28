package com.ace.coupon.service;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.common.transaction.AfterCommitExecutor;
import com.ace.coupon.dto.response.CouponStateChangeResponse;
import com.ace.coupon.entity.CouponHistory;
import com.ace.coupon.entity.CouponIssue;
import com.ace.coupon.entity.CouponStateIdempotency;
import com.ace.coupon.enums.CouponIssueStatus;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.repository.CouponHistoryRepository;
import com.ace.coupon.repository.CouponIssueRepository;
import com.ace.coupon.repository.CouponStateIdempotencyRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CouponStateProcessor {
	private static final String MANUAL_EXPIRED_REASON = "MANUAL_EXPIRED";

	private final CouponIssueRepository couponIssueRepository;
	private final CouponHistoryRepository couponHistoryRepository;
	private final CouponStateIdempotencyRepository idempotencyRepository;
	private final CouponIssueRedisProperties properties;
	private final MeterRegistry meterRegistry;

	// Grafana 범례에 enum/ErrorCode 이름 대신 한글로 표시하기 위한 라벨. Ace_FE 쪽 상태/사유 토글과 문구를 맞춘다.
	// CouponExpirationProcessor도 동일한 라벨을 써야 해서 package-private으로 공유한다.
	static final Map<CouponIssueStatus, String> STATE_LABELS = Map.of(
			CouponIssueStatus.ISSUED, "발급 완료",
			CouponIssueStatus.USED, "사용 완료",
			CouponIssueStatus.EXPIRED, "기간 만료",
			CouponIssueStatus.CANCELED, "취소"
	);

	private static final Map<String, String> STATE_CHANGE_REASON_LABELS = Map.of(
			"ISSUE_NOT_FOUND", "발급 내역 없음",
			"INVALID_REQUEST", "잘못된 요청",
			"EVENT_NOT_OPEN", "오픈 전",
			"ALREADY_EXPIRED", "만료됨",
			"ALREADY_USED", "이미 사용",
			"NOT_YET_USED", "미사용",
			"INVALID_STATE_TRANSITION", "상태 전이 불가"
	);

	@Transactional
	public CouponStateChangeResponse processStateChange(
			Long issueId, Long userId, UUID idempotencyKey,
			CouponIssueStatus targetStatus, String reason) {
		try {
			CouponStateChangeResponse response =
					doProcessStateChange(issueId, userId, idempotencyKey, targetStatus, reason);
			AfterCommitExecutor.execute(() -> meterRegistry.counter("coupon.state.change",
					"result", "success",
					"result_label", "성공",
					"from", response.previousStatus().name(),
					"from_label", STATE_LABELS.get(response.previousStatus()),
					"to", response.currentStatus().name(),
					"to_label", STATE_LABELS.get(response.currentStatus())).increment());
			return response;
		} catch (CouponException exception) {
			String reasonCode = exception.getErrorCode().name();
			meterRegistry.counter("coupon.state.change",
					"result", "fail",
					"result_label", "실패",
					"to", targetStatus.name(),
					"to_label", STATE_LABELS.get(targetStatus),
					"reason", reasonCode,
					"reason_label", STATE_CHANGE_REASON_LABELS.getOrDefault(reasonCode, reasonCode)).increment();
			throw exception;
		}
	}

	private CouponStateChangeResponse doProcessStateChange(
			Long issueId, Long userId, UUID idempotencyKey,
			CouponIssueStatus targetStatus, String reason) {

		String eventUid = idempotencyKey.toString();
		ZoneId zoneId = properties.zoneId();
		LocalDateTime now = LocalDateTime.now(zoneId).truncatedTo(ChronoUnit.MICROS);

		CouponStateIdempotency idempotency = CouponStateIdempotency.builder()
				.eventUid(eventUid)
				.issueId(issueId)
				.userId(userId)
				.targetStatus(targetStatus)
				.createdAt(now)
				.build();
		idempotencyRepository.saveAndFlush(idempotency);

		CouponIssue issue = couponIssueRepository.findByIdForUpdate(issueId)
				.orElseThrow(() -> new CouponException(ErrorCode.ISSUE_NOT_FOUND));

		if (!issue.getUser().getId().equals(userId)) {
			throw new CouponException(ErrorCode.INVALID_REQUEST, "본인의 쿠폰만 상태를 변경할 수 있습니다.");
		}

		validateValidityPeriod(issue, targetStatus, now);

		CouponIssueStatus previousStatus = issue.getStatus();
		applyStateTransition(issue, targetStatus, now);

		CouponHistory history = CouponHistory.builder()
				.couponIssue(issue)
				.fromStatus(previousStatus)
				.toStatus(targetStatus)
				.actor("USER_" + userId)
				.reason(resolveHistoryReason(targetStatus, reason))
				.occurredAt(now)
				.recordedAt(now)
				.eventUid(eventUid)
				.build();
		couponHistoryRepository.save(history);

		idempotency.complete(issue.getCouponEvent().getId(), previousStatus, now);

		return new CouponStateChangeResponse(
				idempotencyKey,
				issue.getId(),
				issue.getCouponEvent().getId(),
				userId,
				previousStatus,
				targetStatus,
				now);
	}

	private String resolveHistoryReason(CouponIssueStatus targetStatus, String requestedReason) {
		if (targetStatus == CouponIssueStatus.EXPIRED) {
			return MANUAL_EXPIRED_REASON;
		}
		if (requestedReason != null) {
			return requestedReason;
		}
		return targetStatus == CouponIssueStatus.ISSUED ? "USE_CANCELED" : "USED";
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
			} else if (targetStatus == CouponIssueStatus.EXPIRED) {
				issue.expire(now);
			}
		} catch (IllegalStateException e) {
			if (targetStatus == CouponIssueStatus.USED && issue.getStatus() == CouponIssueStatus.USED) {
				throw new CouponException(ErrorCode.ALREADY_USED);
			}
			if (targetStatus == CouponIssueStatus.ISSUED && issue.getStatus() == CouponIssueStatus.ISSUED) {
				throw new CouponException(ErrorCode.NOT_YET_USED);
			}
			if (targetStatus == CouponIssueStatus.EXPIRED && issue.getStatus() == CouponIssueStatus.EXPIRED) {
				throw new CouponException(ErrorCode.ALREADY_EXPIRED);
			}
			throw new CouponException(ErrorCode.INVALID_STATE_TRANSITION);
		}
	}
}
