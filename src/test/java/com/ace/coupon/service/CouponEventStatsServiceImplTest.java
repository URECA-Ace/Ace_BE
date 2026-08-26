package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.response.CouponEventStatsResponse;
import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.redis.CouponEventStatsSnapshot;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.redis.RedisCouponEventStatsReader;
import com.ace.coupon.repository.CouponEventRepository;

class CouponEventStatsServiceImplTest {

	private RedisCouponEventStatsReader statsReader;
	private CouponEventRepository couponEventRepository;
	private CouponEventStatsService service;

	@BeforeEach
	void setUp() {
		statsReader = Mockito.mock(RedisCouponEventStatsReader.class);
		couponEventRepository = Mockito.mock(CouponEventRepository.class);
		service = new CouponEventStatsServiceImpl(
				statsReader,
				couponEventRepository,
				new CouponIssueRedisProperties(Duration.ofDays(7), ZoneId.of("Asia/Seoul")));
	}

	@Test
	@DisplayName("Redis 스냅샷을 실시간 발급 현황 응답으로 변환한다")
	void returnsRealtimeStats() {
		Instant observedAt = Instant.parse("2026-08-18T08:30:00Z");
		given(statsReader.read(19L)).willReturn(new CouponEventStatsSnapshot(
				19L, 10_000L, 8_271L, 1_729L, CouponEventStatus.OPEN, observedAt, 8_200L, 71L));

		CouponEventStatsResponse response = service.findStats(19L);

		assertThat(response.eventId()).isEqualTo(19L);
		assertThat(response.totalStock()).isEqualTo(10_000L);
		assertThat(response.allocatedQuantity()).isEqualTo(8_271L);
		assertThat(response.remainingStock()).isEqualTo(1_729L);
		assertThat(response.status()).isEqualTo(CouponEventStatus.OPEN);
		assertThat(response.observedAt().toInstant()).isEqualTo(observedAt);
		assertThat(response.confirmedQuantity()).isEqualTo(8_200L);
		assertThat(response.pendingQuantity()).isEqualTo(71L);
	}

	@Test
	@DisplayName("Redis와 DB에 캠페인이 모두 없으면 EVENT_NOT_FOUND를 반환한다")
	void returnsNotFoundWhenEventDoesNotExist() {
		given(statsReader.read(19L)).willReturn(null);
		given(couponEventRepository.existsById(19L)).willReturn(false);

		assertErrorCode(ErrorCode.EVENT_NOT_FOUND);
	}

	@Test
	@DisplayName("DB 캠페인은 있지만 Redis 현황이 없으면 일시적 조회 불가를 반환한다")
	void returnsUnavailableWhenRedisStateIsMissing() {
		given(statsReader.read(19L)).willReturn(null);
		given(couponEventRepository.existsById(19L)).willReturn(true);

		assertErrorCode(ErrorCode.EVENT_STATS_TEMPORARILY_UNAVAILABLE);
	}

	@Test
	@DisplayName("Redis 연결 실패는 일시적 조회 불가로 변환한다")
	void mapsRedisFailureToUnavailable() {
		given(statsReader.read(19L))
				.willThrow(new DataAccessResourceFailureException("redis unavailable"));

		assertErrorCode(ErrorCode.EVENT_STATS_TEMPORARILY_UNAVAILABLE);
	}

	@Test
	@DisplayName("Redis 현황 데이터 손상은 일시적 조회 불가로 변환한다")
	void mapsCorruptedStateToUnavailable() {
		given(statsReader.read(19L))
				.willThrow(new IllegalStateException("corrupted state"));

		assertErrorCode(ErrorCode.EVENT_STATS_TEMPORARILY_UNAVAILABLE);
	}

	private void assertErrorCode(ErrorCode expected) {
		assertThatThrownBy(() -> service.findStats(19L))
				.isInstanceOfSatisfying(CouponException.class,
						exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
	}
}
