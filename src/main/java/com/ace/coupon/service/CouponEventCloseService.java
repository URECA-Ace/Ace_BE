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

/**
 * 운영자가 캠페인 회차를 예정 시각보다 먼저 마감한다.
 *
 * <p>두 가지를 분리해서 처리한다.
 * <ul>
 *   <li><b>발급 차단</b> — Redis 메타데이터의 {@code closeAt} 을 현재로 당겨 즉시 막는다.
 *       판정이 Redis 에서 이뤄지므로 이 시점부터 신규 발급은 전건 거절된다.</li>
 *   <li><b>상태 전환</b> — {@code CLOSED} 는 검증팀의 Drain 조건이라 파이프라인이 빈 뒤에만 찍어야 한다.
 *       판단을 {@link CouponEventLifecycleService#closeIfDrained} 한 곳에 맡기고,
 *       아직 확정 대기 건이 남아 있으면 상태를 그대로 두고 주기 sweep 에 위임한다.</li>
 * </ul>
 *
 * <p>DB 는 마감 시각만 현재로 당긴다. 그래야 sweep 의 마감 대상 조회
 * ({@code closeAt <= CURRENT_TIMESTAMP}) 에 이 회차가 걸린다.
 */
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
					true);
		}

		CampaignCloseDecision decision = closeInRedis(eventId);
		validateRedisDecision(decision.result());

		LocalDateTime closedAt = toLocalDateTime(decision.observedAt());
		couponEventRepository.advanceCloseAt(eventId, CLOSABLE_STATUSES, closedAt);

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
				statusAfter == CouponEventStatus.CLOSED);
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
			case CLOSED, ALREADY_CLOSED, NOT_INITIALIZED -> {
				// Redis가 없으면 발급도 불가능하다. 마감 시각을 당겨 두면 sweep 이 이어받는다.
			}
			case INVALID_STATE -> throw new CouponException(ErrorCode.INVALID_STATE_TRANSITION);
			case CORRUPTED_STATE, INTERNAL_WRITE_ERROR -> throw new CouponException(
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
