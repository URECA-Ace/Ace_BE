package com.ace.coupon.service;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

@Service
@RequiredArgsConstructor
public class CouponEventCloseService {

	private final CouponEventRepository couponEventRepository;
	private final CampaignRedisCloser campaignRedisCloser;
	private final CouponIssueRedisProperties properties;

	@Transactional
	public CouponEventCloseResponse close(Long eventId) {
		CouponEvent event = couponEventRepository.findById(eventId)
				.orElseThrow(() -> new CouponException(ErrorCode.EVENT_NOT_FOUND));

		if (event.getStatus() != CouponEventStatus.OPEN
				&& event.getStatus() != CouponEventStatus.CLOSED) {
			throw new CouponException(
					ErrorCode.INVALID_STATE_TRANSITION,
					"OPEN 상태의 캠페인만 수동 마감할 수 있습니다.");
		}

		CampaignCloseDecision decision;
		try {
			decision = campaignRedisCloser.close(eventId);
		} catch (RuntimeException exception) {
			throw new CouponException(
					ErrorCode.CAMPAIGN_CLOSE_TEMPORARILY_UNAVAILABLE,
					ErrorCode.CAMPAIGN_CLOSE_TEMPORARILY_UNAVAILABLE.getDefaultMessage(),
					exception);
		}

		validateRedisDecision(decision.result());

		if (event.getStatus() == CouponEventStatus.OPEN) {
			int updated = couponEventRepository.closeOpenEvent(
					eventId,
					CouponEventStatus.OPEN,
					CouponEventStatus.CLOSED);
			if (updated == 0) {
				CouponEventStatus currentStatus = couponEventRepository.findById(eventId)
						.map(CouponEvent::getStatus)
						.orElseThrow(() -> new CouponException(ErrorCode.EVENT_NOT_FOUND));
				if (currentStatus != CouponEventStatus.CLOSED) {
					throw new CouponException(ErrorCode.INVALID_STATE_TRANSITION);
				}
			}
		}

		return new CouponEventCloseResponse(
				eventId,
				CouponEventStatus.CLOSED,
				OffsetDateTime.ofInstant(decision.observedAt(), properties.zoneId()));
	}

	private void validateRedisDecision(CampaignCloseResult result) {
		switch (result) {
			case CLOSED, ALREADY_CLOSED, NOT_INITIALIZED -> {
				// Redis가 없으면 발급도 불가능하다. DB를 CLOSED로 바꾸면 복구 초기화도 차단된다.
			}
			case INVALID_STATE -> throw new CouponException(ErrorCode.INVALID_STATE_TRANSITION);
			case CORRUPTED_STATE, INTERNAL_WRITE_ERROR -> throw new CouponException(
					ErrorCode.CAMPAIGN_CLOSE_TEMPORARILY_UNAVAILABLE);
		}
	}
}
