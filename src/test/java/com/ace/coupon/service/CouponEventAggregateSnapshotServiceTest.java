package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.RedisConnectionFailureException;

import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.redis.CouponEventStatsSnapshot;
import com.ace.coupon.redis.RedisCouponEventStatsReader;
import com.ace.coupon.repository.CouponEventRepository;
import com.ace.coupon.service.CouponEventAggregateSnapshotService.SweepResult;

class CouponEventAggregateSnapshotServiceTest {

	private CouponEventRepository couponEventRepository;
	private RedisCouponEventStatsReader statsReader;
	private CouponEventAggregateSnapshotService service;

	@BeforeEach
	void setUp() {
		couponEventRepository = Mockito.mock(CouponEventRepository.class);
		statsReader = Mockito.mock(RedisCouponEventStatsReader.class);
		service = new CouponEventAggregateSnapshotService(couponEventRepository, statsReader);
	}

	@Test
	@DisplayName("판정 수가 아니라 확정 수를 집계 컬럼에 반영한다")
	void appliesConfirmedQuantityInsteadOfAllocatedQuantity() {
		// 판정 1,000 중 970만 저장이 끝난 상태
		// allocatedQuantity를 쓰면 30만큼 앞서 나간다
		given(statsReader.read(1L)).willReturn(snapshot(1_000L, 1_000L, 970L, 30L));
		given(couponEventRepository.applyAggregateSnapshot(1L, 1_000, 970)).willReturn(1);

		assertThat(service.snapshot(1L)).isEqualTo(CouponEventAggregateSnapshotResult.APPLIED);

		verify(couponEventRepository).applyAggregateSnapshot(1L, 1_000, 970);
	}

	@Test
	@DisplayName("Redis에 판정 데이터가 없으면 집계 컬럼을 건드리지 않고 상태 없음으로 구분한다")
	void reportsNoRedisStateWithoutTouchingAggregate() {

		given(statsReader.read(1L)).willReturn(null);

		assertThat(service.snapshot(1L)).isEqualTo(CouponEventAggregateSnapshotResult.NO_REDIS_STATE);

		verify(couponEventRepository, never()).applyAggregateSnapshot(anyLong(), anyInt(), anyInt());
	}

	@Test
	@DisplayName("Redis 조회가 실패하면 상태 없음이 아니라 읽기 불가로 구분한다")
	void reportsUnreadableWhenRedisReadFails() {
		// 이 둘을 같은 값으로 뭉개면 마감 처리가 장애 중에 회차를 마감해 버린다
		given(statsReader.read(1L)).willThrow(new RedisConnectionFailureException("down"));

		assertThat(service.snapshot(1L)).isEqualTo(CouponEventAggregateSnapshotResult.UNREADABLE);

		verify(couponEventRepository, never()).applyAggregateSnapshot(anyLong(), anyInt(), anyInt());
	}

	@Test
	@DisplayName("현황 값이 손상되어 확정 수가 재고를 넘으면 읽기 불가로 처리한다")
	void reportsUnreadableWhenSnapshotIsCorrupted() {
		given(statsReader.read(1L)).willReturn(snapshot(1_000L, 1_000L, 1_001L, 0L));

		assertThat(service.snapshot(1L)).isEqualTo(CouponEventAggregateSnapshotResult.UNREADABLE);

		verify(couponEventRepository, never()).applyAggregateSnapshot(anyLong(), anyInt(), anyInt());
	}

	@Test
	@DisplayName("조건부 UPDATE가 0행을 바꾸면 반영 없음으로 구분한다")
	void reportsNotModifiedWhenConditionalUpdateMatchesNoRow() {
		given(statsReader.read(1L)).willReturn(snapshot(1_000L, 1_000L, 970L, 30L));
		given(couponEventRepository.applyAggregateSnapshot(1L, 1_000, 970)).willReturn(0);

		assertThat(service.snapshot(1L)).isEqualTo(CouponEventAggregateSnapshotResult.NOT_MODIFIED);
	}

	@Test
	@DisplayName("발급 중인 회차만 훑고 한 회차의 실패가 다음 회차를 막지 않는다")
	void snapshotsActiveEventsAndIsolatesFailures() {
		given(couponEventRepository.findSnapshotTargetEventIds(
				CouponEventStatus.OPEN, PageRequest.of(0, 100)))
				.willReturn(List.of(1L, 2L, 3L));
		given(statsReader.read(1L)).willThrow(new RedisConnectionFailureException("down"));
		given(statsReader.read(2L)).willReturn(null);
		given(statsReader.read(3L)).willReturn(snapshot(1_000L, 400L, 400L, 0L));
		given(couponEventRepository.applyAggregateSnapshot(3L, 1_000, 400)).willReturn(1);

		SweepResult result = service.snapshotActiveEvents();

		assertThat(result.applied()).isOne();
		verify(statsReader).read(3L);
		verify(couponEventRepository).applyAggregateSnapshot(3L, 1_000, 400);
		verify(couponEventRepository, never()).applyAggregateSnapshot(
				Mockito.eq(1L), anyInt(), anyInt());
		verify(couponEventRepository, never()).applyAggregateSnapshot(
				Mockito.eq(2L), anyInt(), anyInt());
	}

	@Test
	@DisplayName("건너뛴 회차를 사유별로 세어 장애를 관측할 수 있게 한다")
	void countsSkippedEventsByReason() {
		// 사유를 나누지 않으면 Redis 전면 장애가 "갱신할 게 없음"과 똑같이 보인다
		given(couponEventRepository.findSnapshotTargetEventIds(
				CouponEventStatus.OPEN, PageRequest.of(0, 100)))
				.willReturn(List.of(1L, 2L, 3L, 4L));
		given(statsReader.read(1L)).willThrow(new RedisConnectionFailureException("down"));
		given(statsReader.read(2L)).willReturn(null);
		given(statsReader.read(3L)).willReturn(snapshot(1_000L, 400L, 400L, 0L));
		given(statsReader.read(4L)).willReturn(snapshot(1_000L, 400L, 400L, 0L));
		given(couponEventRepository.applyAggregateSnapshot(3L, 1_000, 400)).willReturn(1);
		given(couponEventRepository.applyAggregateSnapshot(4L, 1_000, 400)).willReturn(0);

		SweepResult result = service.snapshotActiveEvents();

		assertThat(result.scanned()).isEqualTo(4);
		assertThat(result.applied()).isOne();
		assertThat(result.notModified()).isOne();
		assertThat(result.noRedisState()).isOne();
		assertThat(result.unreadable()).isOne();
		assertThat(result.hasAnomaly()).isTrue();
	}

	@Test
	@DisplayName("전부 정상 반영되면 이상 징후로 보지 않는다")
	void doesNotReportAnomalyWhenEveryEventIsApplied() {
		given(couponEventRepository.findSnapshotTargetEventIds(
				CouponEventStatus.OPEN, PageRequest.of(0, 100)))
				.willReturn(List.of(1L));
		given(statsReader.read(1L)).willReturn(snapshot(1_000L, 400L, 400L, 0L));
		given(couponEventRepository.applyAggregateSnapshot(1L, 1_000, 400)).willReturn(1);

		assertThat(service.snapshotActiveEvents().hasAnomaly()).isFalse();
	}

	@Test
	@DisplayName("이미 읽어 둔 현황을 다시 읽지 않고 그대로 반영한다")
	void appliesPreloadedSnapshotWithoutReadingRedisAgain() {
		CouponEventStatsSnapshot preloaded = snapshot(1_000L, 1_000L, 1_000L, 0L);
		given(couponEventRepository.applyAggregateSnapshot(1L, 1_000, 1_000)).willReturn(1);

		assertThat(service.apply(1L, preloaded)).isEqualTo(CouponEventAggregateSnapshotResult.APPLIED);

		verify(statsReader, never()).read(any());
	}

	private CouponEventStatsSnapshot snapshot(
			long totalStock,
			long allocatedQuantity,
			long confirmedQuantity,
			long pendingQuantity) {
		return new CouponEventStatsSnapshot(
				1L,
				totalStock,
				allocatedQuantity,
				totalStock - allocatedQuantity,
				CouponEventStatus.OPEN,
				Instant.parse("2026-08-26T03:00:00Z"),
				confirmedQuantity,
				pendingQuantity);
	}
}
