package com.ace.coupon.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.redis.CampaignInitializationResult;
import com.ace.coupon.redis.CampaignRedisInitializer;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.repository.CouponEventRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignRedisInitializationRecoveryService {

	private static final List<CouponEventStatus> RECOVERABLE_STATUSES = List.of(
			CouponEventStatus.SCHEDULED,
			CouponEventStatus.OPEN);

	private final CouponEventRepository couponEventRepository;
	private final CampaignRedisInitializer campaignRedisInitializer;
	private final CouponIssueRedisProperties properties;

	public int recoverActiveCampaigns() {
		LocalDateTime now = LocalDateTime.now(properties.zoneId());
		List<CouponEvent> campaigns = couponEventRepository
				.findAllByStatusInAndCloseAtAfter(RECOVERABLE_STATUSES, now);

		int recoveredCount = 0;
		for (CouponEvent campaign : campaigns) {
			if (recover(campaign)) {
				recoveredCount++;
			}
		}
		return recoveredCount;
	}

	private boolean recover(CouponEvent campaign) {
		try {
			CampaignInitializationResult result = campaignRedisInitializer.initialize(campaign);
			if (result == CampaignInitializationResult.INITIALIZED) {
				log.info("쿠폰 캠페인 Redis 상태를 복구했습니다. eventId={}", campaign.getId());
				return true;
			}
			if (result == CampaignInitializationResult.ALREADY_INITIALIZED) {
				return false;
			}
			log.error("쿠폰 캠페인 Redis 상태를 복구하지 못했습니다. eventId={}, result={}",
					campaign.getId(), result);
			return false;
		} catch (DataAccessException | IllegalStateException | IllegalArgumentException exception) {
			log.error("쿠폰 캠페인 Redis 상태 복구 중 오류가 발생했습니다. eventId={}",
					campaign.getId(), exception);
			return false;
		}
	}
}
