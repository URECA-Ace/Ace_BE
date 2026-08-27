package com.ace.coupon.scheduler;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.ace.coupon.service.CampaignRedisInitializationRecoveryService;

class CampaignRedisInitializationRecoverySchedulerTest {

	@Test
	@DisplayName("복구 스케줄러가 활성 캠페인 Redis 초기화를 요청한다")
	void delegatesRecovery() {
		CampaignRedisInitializationRecoveryService service =
				Mockito.mock(CampaignRedisInitializationRecoveryService.class);
		CampaignRedisInitializationRecoveryScheduler scheduler =
				new CampaignRedisInitializationRecoveryScheduler(service);

		scheduler.recoverActiveCampaigns();

		verify(service).recoverActiveCampaigns();
	}
}
