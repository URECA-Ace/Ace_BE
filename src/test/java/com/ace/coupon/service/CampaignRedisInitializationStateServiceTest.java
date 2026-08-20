package com.ace.coupon.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.repository.CampaignRedisInitializationRepository;

@ExtendWith(MockitoExtension.class)
class CampaignRedisInitializationStateServiceTest {

	@Mock
	private CampaignRedisInitializationRepository repository;

	private CampaignRedisInitializationStateService service;

	@BeforeEach
	void setUp() {
		service = new CampaignRedisInitializationStateService(
				repository,
				new CouponIssueRedisProperties(Duration.ofDays(7), ZoneId.of("Asia/Seoul")));
	}

	@Test
	@DisplayName("Redis 초기화 시도와 성공을 별도 트랜잭션용 저장소에 기록한다")
	void recordsAttemptAndSuccess() {
		given(repository.recordAttempt(eq(1L), any())).willReturn(1);
		given(repository.recordSuccess(eq(1L), any())).willReturn(1);

		service.recordAttempt(1L);
		service.recordSuccess(1L);

		verify(repository).recordAttempt(eq(1L), any());
		verify(repository).recordSuccess(eq(1L), any());
	}

	@Test
	@DisplayName("Redis 오류 메시지는 DB 컬럼 길이에 맞춰 잘라 기록한다")
	void truncatesLongFailureMessage() {
		service.recordFailure(1L, "REDIS_CALL_FAILED", "x".repeat(700));

		ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
		verify(repository).recordFailure(
				eq(1L), eq("REDIS_CALL_FAILED"), messageCaptor.capture(), any());
		org.assertj.core.api.Assertions.assertThat(messageCaptor.getValue()).hasSize(500);
	}
}
