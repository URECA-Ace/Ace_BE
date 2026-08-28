package com.ace.coupon.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
class CampaignRedisCloserTest {

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private RedisScript<List> closeScript;

	@Mock
	private RedisLuaFailureObserver failureObserver;

	private CampaignRedisCloser closer;

	@BeforeEach
	void setUp() {
		closer = new CampaignRedisCloser(redisTemplate, closeScript, failureObserver);
	}

	@Test
	@DisplayName("Lua 마감 결과와 실제 마감 시각을 반환한다")
	void closesUsingRedisServerTime() {
		long closedAt = Instant.parse("2026-08-27T01:00:00Z").toEpochMilli();
		given(redisTemplate.execute(any(RedisScript.class), anyList()))
				.willReturn(List.of(0L, closedAt, 0L, ""));

		CampaignCloseDecision decision = closer.close(51L);

		assertThat(decision.result()).isEqualTo(CampaignCloseResult.CLOSED);
		assertThat(decision.closedAt()).isEqualTo(Instant.ofEpochMilli(closedAt));
		verify(failureObserver).observe(RedisLuaDiagnosticStage.NONE, "CLOSED", "");
	}
}
