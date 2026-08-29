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
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.RedisConnectionFailureException;

import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.redis.CouponEventStatsSnapshot;
import com.ace.coupon.redis.RedisCouponEventStatsReader;
import com.ace.coupon.repository.CouponEventRepository;
import com.ace.coupon.service.CouponEventLifecycleService.SweepResult;

class CouponEventLifecycleServiceTest {

	private static final List<CouponEventStatus> DRAIN_REQUIRED =
			List.of(CouponEventStatus.OPEN, CouponEventStatus.SOLD_OUT);

	private CouponEventRepository couponEventRepository;
	private RedisCouponEventStatsReader statsReader;
	private CouponEventAggregateSnapshotService snapshotService;
	private ApplicationEventPublisher eventPublisher;
	private CouponEventLifecycleService service;

	@BeforeEach
	void setUp() {
		couponEventRepository = Mockito.mock(CouponEventRepository.class);
		statsReader = Mockito.mock(RedisCouponEventStatsReader.class);
		snapshotService = Mockito.mock(CouponEventAggregateSnapshotService.class);
		eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
		service = new CouponEventLifecycleService(
				couponEventRepository, statsReader, snapshotService, eventPublisher);

		given(couponEventRepository.findSnapshotTargetEventIds(any(), anyLong(), any()))
				.willReturn(List.of());
		given(couponEventRepository.findCloseTargetEventIds(any(), anyLong(), any()))
				.willReturn(List.of());
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
				7L, DRAIN_REQUIRED, CouponEventStatus.CLOSED, 1_000, 800)).willReturn(1);

		assertThat(service.sweep().closed()).isOne();

		InOrder inOrder = Mockito.inOrder(snapshotService, couponEventRepository);
		inOrder.verify(snapshotService).apply(7L, drained);
		inOrder.verify(couponEventRepository).markClosed(
				7L, DRAIN_REQUIRED, CouponEventStatus.CLOSED, 1_000, 800);
	}

	@Test
	@DisplayName("마감 시각이 지나도 저장 중인 건이 남아 있으면 마감하지 않는다")
	void waitsForDrainBeforeClosing() {
		givenCloseTargets(7L);
		given(statsReader.read(7L)).willReturn(snapshot(1_000L, 800L, 200L, 780L, 20L));

		SweepResult result = service.sweep();

		assertThat(result.closed()).isZero();
		assertThat(result.waitingForDrain()).isOne();
		verify(couponEventRepository, never()).markClosed(anyLong(), any(), any(), anyInt(), anyInt());
	}

	@Test
	@DisplayName("마감 대상인데 Redis 현황이 없으면 마감을 보류하고 이상으로 센다")
	void holdsCloseWhenRedisStateIsMissing() {
		// 초기화 실패나 키 유실과 구분되지 않는다.
		// 그대로 마감하면 확정되지 않은 집계를 신뢰하게 된다
		givenCloseTargets(7L);
		given(statsReader.read(7L)).willReturn(null);

		SweepResult result = service.sweep();

		assertThat(result.closed()).isZero();
		assertThat(result.unresolved()).isOne();
		verify(couponEventRepository, never()).markClosed(anyLong(), any(), any(), anyInt(), anyInt());
	}

	@Test
	@DisplayName("Redis 장애로 현황을 못 읽으면 마감하지 않고 다음 실행으로 미룬다")
	void doesNotCloseWhileRedisIsUnreadable() {
		givenCloseTargets(7L);
		given(statsReader.read(7L)).willThrow(new RedisConnectionFailureException("down"));

		SweepResult result = service.sweep();

		assertThat(result.closed()).isZero();
		assertThat(result.unresolved()).isOne();
		verify(couponEventRepository, never()).markClosed(anyLong(), any(), any(), anyInt(), anyInt());
	}

	@Test
	@DisplayName("집계 반영이 거부되면 상태를 진행시키지 않는다")
	void keepsStatusWhenAggregateIsRejected() {
		// 재고 설정 불일치나 확정 수 역헹
		CouponEventStatsSnapshot drained = snapshot(1_000L, 1_000L, 0L, 1_000L, 0L);
		givenIssuingEvents(1L);
		given(statsReader.read(1L)).willReturn(drained);
		given(snapshotService.apply(1L, drained))
				.willReturn(CouponEventAggregateSnapshotResult.REJECTED);

		assertThat(service.sweep().soldOut()).isZero();

		verify(couponEventRepository, never()).markSoldOut(anyLong(), any(), any());
	}

	@Test
	@DisplayName("최종 스냅샷을 읽을 수 없으면 상태를 진행시키지 않는다")
	void keepsStatusWhenFinalSnapshotIsUnreadable() {
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
	void proceedsWhenAggregateIsAlreadyApplied() {
		CouponEventStatsSnapshot drained = snapshot(1_000L, 1_000L, 0L, 1_000L, 0L);
		givenIssuingEvents(1L);
		given(statsReader.read(1L)).willReturn(drained);
		given(snapshotService.apply(1L, drained))
				.willReturn(CouponEventAggregateSnapshotResult.ALREADY_APPLIED);
		given(couponEventRepository.markSoldOut(
				1L, CouponEventStatus.OPEN, CouponEventStatus.SOLD_OUT)).willReturn(1);

		assertThat(service.sweep().soldOut()).isOne();
	}

	@Test
	@DisplayName("집계가 최종 스냅샷과 다르면 마감 UPDATE 가 0행이라 마감되지 않는다")
	void doesNotCountCloseWhenAggregateGuardRejects() {
		CouponEventStatsSnapshot drained = snapshot(1_000L, 800L, 200L, 800L, 0L);
		givenCloseTargets(7L);
		given(statsReader.read(7L)).willReturn(drained);
		given(snapshotService.apply(7L, drained))
				.willReturn(CouponEventAggregateSnapshotResult.APPLIED);
		given(couponEventRepository.markClosed(
				7L, DRAIN_REQUIRED, CouponEventStatus.CLOSED, 1_000, 800)).willReturn(0);

		assertThat(service.sweep().closed()).isZero();
	}

	@Test
	@DisplayName("한 번도 열리지 못한 SCHEDULED 회차는 Redis 현황 없이 마감한다")
	void closesNeverOpenedEventWithoutRedisState() {
		// openDueEvents 는 closeAt 이 지난 회차를 열지 않으므로 그대로 두면 영원히 마감되지 않는다
		given(couponEventRepository.findCloseTargetEventIds(
				Mockito.eq(List.of(CouponEventStatus.SCHEDULED)), anyLong(), any()))
				.willReturn(List.of(9L), List.of());
		given(couponEventRepository.markScheduledClosed(
				9L, CouponEventStatus.SCHEDULED, CouponEventStatus.CLOSED)).willReturn(1);

		assertThat(service.sweep().closed()).isOne();

		verify(statsReader, never()).read(9L);
	}

	@Test
	@DisplayName("대상이 한 페이지를 넘으면 커서로 다음 페이지까지 훑는다")
	void sweepsBeyondTheFirstPage() {
		// 첫 페이지만 보면 앞의 회차가 계속 대상으로 남을 때 뒤쪽 회차가 영원히 처리되지 않는다
		List<Long> firstPage = new java.util.ArrayList<>();
		for (long id = 1; id <= 100; id++) {
			firstPage.add(id);
		}
		given(couponEventRepository.findSnapshotTargetEventIds(
				Mockito.eq(CouponEventStatus.OPEN), Mockito.eq(0L), any()))
				.willReturn(firstPage);
		given(couponEventRepository.findSnapshotTargetEventIds(
				Mockito.eq(CouponEventStatus.OPEN), Mockito.eq(100L), any()))
				.willReturn(List.of(101L));
		given(statsReader.read(anyLong())).willReturn(null);

		service.sweep();

		verify(statsReader).read(101L);
	}

	private void givenIssuingEvents(Long... eventIds) {
		given(couponEventRepository.findSnapshotTargetEventIds(
				Mockito.eq(CouponEventStatus.OPEN), Mockito.eq(0L), any()))
				.willReturn(List.of(eventIds));
	}

	private void givenCloseTargets(Long... eventIds) {
		given(couponEventRepository.findCloseTargetEventIds(
				Mockito.eq(DRAIN_REQUIRED), anyLong(), any()))
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
