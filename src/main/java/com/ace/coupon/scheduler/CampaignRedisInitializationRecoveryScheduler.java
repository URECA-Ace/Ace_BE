package com.ace.coupon.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ace.coupon.service.CampaignRedisInitializationRecoveryService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
		prefix = "coupon.campaign.redis-initialization-recovery",
		name = "enabled",
		havingValue = "true")
public class CampaignRedisInitializationRecoveryScheduler {

	private final CampaignRedisInitializationRecoveryService recoveryService;

	@Scheduled(
			initialDelayString = "${coupon.campaign.redis-initialization-recovery.initial-delay-ms:5000}",
			fixedDelayString = "${coupon.campaign.redis-initialization-recovery.fixed-delay-ms:10000}")
	public void recoverActiveCampaigns() {
		recoveryService.recoverActiveCampaigns();
	}
}
