package com.ace.coupon.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.response.CouponEventCloseResponse;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.redis.CampaignCloseDecision;
import com.ace.coupon.redis.CampaignCloseResult;
import com.ace.coupon.redis.CampaignRedisCloser;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.repository.CouponEventRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 운영자가 캠페인 회차를 예정 시각보다 먼저 마감한다
// 발급 차단 : Redis 의 closeAt 을 당겨 즉시 막는다. 판정이 Redis 라 이 시점부터 전건 거절
// 상태 전환 : CLOSED 는 Drain 조건이라 closeIfDrained 한 곳에만 맡긴다
//            확정 대기 건이 남아 있으면 상태를 두고 주기 sweep 에 위임
// DB 는 마감 시각만 당긴다. 그래야 sweep 의 마감 대상 조회에 이 회차가 걸린다
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponEventCloseService {

	// 수동 마감을 받을 수 있는 상태
	// SOLD_OUT 은 재고만 소진됐을 뿐 아직 마감 시각 전이라 앞당길 대상이 된다
	private static final List<CouponEventStatus> CLOSABLE_STATUSES = List.of(
			CouponEventStatus.OPEN,
			CouponEventStatus.SOLD_OUT);

	private final CouponEventRepository couponEventRepository;
	private final CampaignRedisCloser campaignRedisCloser;
	private final CouponEventLifecycleService lifecycleService;
	private final CouponIssueRedisProperties properties;

	public CouponEventCloseResponse close(Long eventId) {
		CouponEventStatus statusBefore = requireClosableStatus(eventId);

		// 이미 CLOSED 면 Redis 도 건드리지 않고 그대로 돌려준다
		if (statusBefore == CouponEventStatus.CLOSED) {
			CouponEvent event = findEvent(eventId);
			return new CouponEventCloseResponse(
					eventId,
					CouponEventStatus.CLOSED,
					toOffsetDateTime(event.getCloseAt()),
					true,
					true);
		}

		CampaignCloseDecision decision = closeInRedis(eventId);
		validateRedisDecision(decision.result());

		LocalDateTime closedAt = toLocalDateTime(decision.closedAt());
		boolean closeAtAdvanced = advanceCloseAt(eventId, closedAt);

		// 상태 전환은 Drain 을 확인하는 한 경로로만
		CouponEventLifecycleService.CloseAttempt attempt = lifecycleService.closeIfDrained(eventId);
		if (attempt != CouponEventLifecycleService.CloseAttempt.CLOSED) {
			log.info("수동 마감으로 발급은 차단했지만 회차 상태는 아직 진행시키지 않았습니다. eventId={}, attempt={}",
					eventId, attempt);
		}

		CouponEventStatus statusAfter = findEvent(eventId).getStatus();
		return new CouponEventCloseResponse(
				eventId,
				statusAfter,
				toOffsetDateTime(closedAt),
				statusAfter == CouponEventStatus.CLOSED,
				closeAtAdvanced);
	}

	// Redis 차단은 이미 끝났고 되돌릴 수 없으니 실패를 남김
	// 0행  : 발급은 막혔고 원래 closeAt 이 되면 sweep 이 회수하므로 실패는 아니다. 응답으로만 알린다
	// 예외 : Redis 만 닫힌 채 남음
	private boolean advanceCloseAt(Long eventId, LocalDateTime closedAt) {
		int updatedCount;
		try {
			updatedCount = couponEventRepository.advanceCloseAt(eventId, CLOSABLE_STATUSES, closedAt);
		} catch (RuntimeException exception) {
			log.error("Redis 발급은 차단했지만 DB 마감 시각을 당기지 못했습니다. "
							+ "회차는 원래 마감 시각이 되어야 sweep 에 잡힙니다. eventId={}, redisClosedAt={}",
					eventId, closedAt, exception);
			throw new CouponException(
					ErrorCode.CAMPAIGN_CLOSE_TEMPORARILY_UNAVAILABLE,
					ErrorCode.CAMPAIGN_CLOSE_TEMPORARILY_UNAVAILABLE.getDefaultMessage(),
					exception);
		}

		if (updatedCount == 0) {
			log.warn("Redis 발급은 차단했지만 DB 마감 시각은 그대로입니다. "
							+ "이미 마감 시각이 지났거나 그 사이 상태가 바뀐 회차입니다. eventId={}, redisClosedAt={}",
					eventId, closedAt);
			return false;
		}
		return true;
	}

	private CouponEventStatus requireClosableStatus(Long eventId) {
		CouponEventStatus status = findEvent(eventId).getStatus();
		if (status != CouponEventStatus.CLOSED && !CLOSABLE_STATUSES.contains(status)) {
			throw new CouponException(
					ErrorCode.INVALID_STATE_TRANSITION,
					"발급이 시작된 캠페인만 수동 마감할 수 있습니다.");
		}
		return status;
	}

	private CouponEvent findEvent(Long eventId) {
		return couponEventRepository.findById(eventId)
				.orElseThrow(() -> new CouponException(ErrorCode.EVENT_NOT_FOUND));
	}

	private CampaignCloseDecision closeInRedis(Long eventId) {
		try {
			return campaignRedisCloser.close(eventId);
		} catch (RuntimeException exception) {
			throw new CouponException(
					ErrorCode.CAMPAIGN_CLOSE_TEMPORARILY_UNAVAILABLE,
					ErrorCode.CAMPAIGN_CLOSE_TEMPORARILY_UNAVAILABLE.getDefaultMessage(),
					exception);
		}
	}

	private void validateRedisDecision(CampaignCloseResult result) {
		switch (result) {
			case CLOSED, ALREADY_CLOSED -> {
				// Redis가 반환한 실제 마감 시각을 DB에 반영한다.
			}
			case INVALID_STATE -> throw new CouponException(ErrorCode.INVALID_STATE_TRANSITION);
			case NOT_INITIALIZED, CORRUPTED_STATE, INTERNAL_WRITE_ERROR -> throw new CouponException(
					ErrorCode.CAMPAIGN_CLOSE_TEMPORARILY_UNAVAILABLE);
		}
	}

	private LocalDateTime toLocalDateTime(Instant instant) {
		return LocalDateTime.ofInstant(instant, properties.zoneId());
	}

	private OffsetDateTime toOffsetDateTime(LocalDateTime dateTime) {
		return dateTime.atZone(properties.zoneId()).toOffsetDateTime();
	}
}
