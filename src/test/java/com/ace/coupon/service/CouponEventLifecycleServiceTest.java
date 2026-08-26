package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.RedisConnectionFailureException;

import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.redis.CouponEventStatsSnapshot;
import com.ace.coupon.redis.RedisCouponEventStatsReader;
import com.ace.coupon.repository.CouponEventRepository;
import com.ace.coupon.service.CouponEventLifecycleService.SweepResult;

class CouponEventLifecycleServiceTest {

	private CouponEventRepository couponEventRepository;
	private RedisCouponEventStatsReader statsReader;
	private CouponEventAggregateSnapshotService snapshotService;
	private CouponEventLifecycleService service;

	@BeforeEach
	void setUp() {
		couponEventRepository = Mockito.mock(CouponEventRepository.class);
		statsReader = Mockito.mock(RedisCouponEventStatsReader.class);
		snapshotService = Mockito.mock(CouponEventAggregateSnapshotService.class);
		service = new CouponEventLifecycleService(
				couponEventRepository, statsReader, snapshotService);

		given(couponEventRepository.findSnapshotTargetEventIds(any(), any())).willReturn(List.of());
		given(couponEventRepository.findCloseTargetEventIds(any(), any())).willReturn(List.of());
	}

	@Test
	@DisplayName("재고가 소진되고 저장까지 끝나면 최종 스냅샷을 반영한 뒤 소진 처리한다")
	void marksSoldOutAfterPipelineIsDrained() {
		CouponEventStatsSnapshot drained = snapshot(1_000L, 1_000L, 0L, 1_000L, 0L);
		givenIssuingEvents(1L);
		given(statsReader.read(1L)).willReturn(drained);
		given(snapshotService.apply(1L, drained))
				.willReturn(CouponEventAggregateSnapshotResult.APPLIED);
		given(couponEventRepository.markSoldOut(
				1L, CouponEventStatus.OPEN, CouponEventStatus.SOLD_OUT)).willReturn(1);

		assertThat(service.sweep().soldOut()).isOne();

		// 스냅샷이 상태 전환보다 먼저여야 검증이 확정된 값을 읽는다
		InOrder inOrder = Mockito.inOrder(snapshotService, couponEventRepository);
		inOrder.verify(snapshotService).apply(1L, drained);
		inOrder.verify(couponEventRepository).markSoldOut(
				1L, CouponEventStatus.OPEN, CouponEventStatus.SOLD_OUT);
	}

	@Test
	@DisplayName("재고가 소진돼도 저장 중인 건이 남아 있으면 소진 처리하지 않는다")
	void waitsForDrainBeforeMarkingSoldOut() {
		// 판정 1,000 / 확정 970 / 대기 30
		givenIssuingEvents(1L);
		given(statsReader.read(1L)).willReturn(snapshot(1_000L, 1_000L, 0L, 970L, 30L));

		SweepResult result = service.sweep();

		assertThat(result.soldOut()).isZero();
		assertThat(result.waitingForDrain()).isOne();
		verify(couponEventRepository, never()).markSoldOut(anyLong(), any(), any());
		verify(snapshotService, never()).apply(anyLong(), any());
	}

	@Test
	@DisplayName("재고가 남아 있으면 소진 처리 대상이 아니다")
	void doesNotMarkSoldOutWhileStockRemains() {
		givenIssuingEvents(1L);
		given(statsReader.read(1L)).willReturn(snapshot(1_000L, 400L, 600L, 400L, 0L));

		SweepResult result = service.sweep();

		assertThat(result.soldOut()).isZero();
		assertThat(result.waitingForDrain()).isZero();
		verify(couponEventRepository, never()).markSoldOut(anyLong(), any(), any());
	}

	@Test
	@DisplayName("마감 시각이 지나고 저장까지 끝나면 최종 스냅샷을 반영한 뒤 마감한다")
	void closesDueEventAfterPipelineIsDrained() {
		CouponEventStatsSnapshot drained = snapshot(1_000L, 800L, 200L, 800L, 0L);
		givenCloseTargets(7L);
		given(statsReader.read(7L)).willReturn(drained);
		given(snapshotService.apply(7L, drained))
				.willReturn(CouponEventAggregateSnapshotResult.APPLIED);
		given(couponEventRepository.markClosed(
				Mockito.eq(7L), any(), Mockito.eq(CouponEventStatus.CLOSED))).willReturn(1);

		assertThat(service.sweep().closed()).isOne();

		InOrder inOrder = Mockito.inOrder(snapshotService, couponEventRepository);
		inOrder.verify(snapshotService).apply(7L, drained);
		inOrder.verify(couponEventRepository).markClosed(
				Mockito.eq(7L), any(), Mockito.eq(CouponEventStatus.CLOSED));
	}

	@Test
	@DisplayName("마감 시각이 지나도 저장 중인 건이 남아 있으면 마감하지 않는다")
	void waitsForDrainBeforeClosing() {
		givenCloseTargets(7L);
		given(statsReader.read(7L)).willReturn(snapshot(1_000L, 800L, 200L, 780L, 20L));

		SweepResult result = service.sweep();

		assertThat(result.closed()).isZero();
		assertThat(result.waitingForDrain()).isOne();
		verify(couponEventRepository, never()).markClosed(anyLong(), any(), any());
	}

	@Test
	@DisplayName("Redis 상태가 이미 사라진 지난 회차는 스냅샷 없이 상태만 마감한다")
	void closesEventWithoutRedisStateWithoutTouchingAggregate() {
		// 지난 회차의 집계는 이미 맞는 값
		givenCloseTargets(7L);
		given(statsReader.read(7L)).willReturn(null);
		given(couponEventRepository.markClosed(
				Mockito.eq(7L), any(), Mockito.eq(CouponEventStatus.CLOSED))).willReturn(1);

		assertThat(service.sweep().closed()).isOne();

		verify(snapshotService, never()).apply(anyLong(), any());
	}

	@Test
	@DisplayName("Redis 장애로 현황을 못 읽으면 마감하지 않고 다음 실행으로 미룬다")
	void doesNotCloseWhileRedisIsUnreadable() {
		// 상태 없음과 읽기 실패를 뭉개면 장애 중에 확정 전 값으로 마감
		givenCloseTargets(7L);
		given(statsReader.read(7L)).willThrow(new RedisConnectionFailureException("down"));

		SweepResult result = service.sweep();

		assertThat(result.closed()).isZero();
		assertThat(result.unreadable()).isOne();
		verify(couponEventRepository, never()).markClosed(anyLong(), any(), any());
	}

	@Test
	@DisplayName("최종 스냅샷 반영에 실패하면 상태를 진행시키지 않는다")
	void keepsStatusWhenFinalSnapshotFails() {
		CouponEventStatsSnapshot corrupted = snapshot(1_000L, 1_000L, 0L, 1_000L, 0L);
		givenIssuingEvents(1L);
		given(statsReader.read(1L)).willReturn(corrupted);
		given(snapshotService.apply(1L, corrupted))
				.willReturn(CouponEventAggregateSnapshotResult.UNREADABLE);

		assertThat(service.sweep().soldOut()).isZero();

		verify(couponEventRepository, never()).markSoldOut(anyLong(), any(), any());
	}

	@Test
	@DisplayName("이미 반영돼 바뀔 값이 없어도 상태 전환은 진행한다")
	void proceedsWhenAggregateIsAlreadyUpToDate() {
		CouponEventStatsSnapshot drained = snapshot(1_000L, 1_000L, 0L, 1_000L, 0L);
		givenIssuingEvents(1L);
		given(statsReader.read(1L)).willReturn(drained);
		given(snapshotService.apply(1L, drained))
				.willReturn(CouponEventAggregateSnapshotResult.NOT_MODIFIED);
		given(couponEventRepository.markSoldOut(
				1L, CouponEventStatus.OPEN, CouponEventStatus.SOLD_OUT)).willReturn(1);

		assertThat(service.sweep().soldOut()).isOne();
	}

	@Test
	@DisplayName("마감 대상에는 한 번도 열리지 못한 SCHEDULED 회차도 포함한다")
	void includesScheduledEventsThatNeverOpened() {
		// openDueEvents 는 closeAt 이 지난 회차를 열지 않으므로 그대로 두면 영원히 마감되지 않는다
		givenCloseTargets(7L);
		given(statsReader.read(7L)).willReturn(null);
		given(couponEventRepository.markClosed(
				Mockito.eq(7L), any(), Mockito.eq(CouponEventStatus.CLOSED))).willReturn(1);

		service.sweep();

		verify(couponEventRepository).findCloseTargetEventIds(
				Mockito.eq(List.of(
						CouponEventStatus.SCHEDULED,
						CouponEventStatus.OPEN,
						CouponEventStatus.SOLD_OUT)),
				any());
	}

	private void givenIssuingEvents(Long... eventIds) {
		given(couponEventRepository.findSnapshotTargetEventIds(
				CouponEventStatus.OPEN, PageRequest.of(0, 100)))
				.willReturn(List.of(eventIds));
	}

	private void givenCloseTargets(Long... eventIds) {
		given(couponEventRepository.findCloseTargetEventIds(any(), any()))
				.willReturn(List.of(eventIds));
	}

	private CouponEventStatsSnapshot snapshot(
			long totalStock,
			long allocatedQuantity,
			long remainingStock,
			long confirmedQuantity,
			long pendingQuantity) {
		return new CouponEventStatsSnapshot(
				1L,
				totalStock,
				allocatedQuantity,
				remainingStock,
				CouponEventStatus.OPEN,
				Instant.parse("2026-08-26T03:00:00Z"),
				confirmedQuantity,
				pendingQuantity);
	}
}
