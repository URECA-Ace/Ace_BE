package com.ace.coupon.service;

import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.redis.CouponEventStatsSnapshot;
import com.ace.coupon.redis.RedisCouponEventStatsReader;
import com.ace.coupon.repository.CouponEventRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 회차 상태를 SOLD_OUT / CLOSED 로 진행시킴
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponEventLifecycleService {

	private static final int SWEEP_BATCH_SIZE = 100;

	// 마감 대상
	// SCHEDULED: 오픈 스케줄러가 멈춰 한 번도 열리지 못한 회차
	private static final List<CouponEventStatus> CLOSE_TARGET_STATUSES = List.of(
			CouponEventStatus.SCHEDULED,
			CouponEventStatus.OPEN,
			CouponEventStatus.SOLD_OUT);

	private final CouponEventRepository couponEventRepository;
	private final RedisCouponEventStatsReader statsReader;
	private final CouponEventAggregateSnapshotService snapshotService;

	public SweepResult sweep() {
		SweepResult soldOut = markDrainedEventsSoldOut();
		SweepResult closed = closeDrainedDueEvents();
		return soldOut.merge(closed);
	}

	// 재고가 소진되고 저장까지 끝난 회차를 SOLD_OUT 으로 전환
	private SweepResult markDrainedEventsSoldOut() {
		List<Long> eventIds = couponEventRepository.findSnapshotTargetEventIds(
				CouponEventStatus.OPEN,
				PageRequest.of(0, SWEEP_BATCH_SIZE));

		int soldOut = 0;
		int waitingForDrain = 0;
		int unreadable = 0;
		for (Long eventId : eventIds) {
			StatsRead read = readStats(eventId);
			if (read.unreadable()) {
				unreadable++;
				continue;
			}
			if (read.snapshot() == null) {
				// Redis 상태가 없으면 재고 소진 여부를 알 수 없다. 마감 시각이 오면 마감 경로가 처리한다
				continue;
			}
			if (!isStockExhausted(read.snapshot())) {
				continue;
			}
			if (!isDrained(read.snapshot())) {
				// 재고는 다 나갔지만 아직 저장 중인 건이 있다. 지금 찍으면 확정 전 값으로 마감된다
				waitingForDrain++;
				continue;
			}
			if (applyFinalSnapshot(eventId, read.snapshot()) && markSoldOut(eventId)) {
				soldOut++;
			}
		}
		return new SweepResult(soldOut, 0, waitingForDrain, unreadable);
	}

	// 마감 시각이 지나고 저장까지 끝난 회차를 CLOSED 로 전환
	private SweepResult closeDrainedDueEvents() {
		List<Long> eventIds = couponEventRepository.findCloseTargetEventIds(
				CLOSE_TARGET_STATUSES,
				PageRequest.of(0, SWEEP_BATCH_SIZE));

		int closed = 0;
		int waitingForDrain = 0;
		int unreadable = 0;
		for (Long eventId : eventIds) {
			StatsRead read = readStats(eventId);
			if (read.unreadable()) {
				// Redis 장애 중
				unreadable++;
				continue;
			}
			if (read.snapshot() == null) {
				// 지난 회차라 Redis 상태가 이미 사라짐
				// 상태만 진행
				if (markClosed(eventId)) {
					closed++;
				}
				continue;
			}
			if (!isDrained(read.snapshot())) {
				waitingForDrain++;
				continue;
			}
			if (applyFinalSnapshot(eventId, read.snapshot()) && markClosed(eventId)) {
				closed++;
			}
		}
		return new SweepResult(0, closed, waitingForDrain, unreadable);
	}

	private boolean isStockExhausted(CouponEventStatsSnapshot snapshot) {
		return snapshot.remainingStock() != null && snapshot.remainingStock() == 0L;
	}

	// 판정 수와 확정 수가 같다는 뜻
	// 이때의 집계가 MySQL 행 수와 정확히 일치
	private boolean isDrained(CouponEventStatsSnapshot snapshot) {
		return snapshot.pendingQuantity() != null && snapshot.pendingQuantity() == 0L;
	}

	// 상태를 바꾸기 전에 집계를 확정
	private boolean applyFinalSnapshot(Long eventId, CouponEventStatsSnapshot snapshot) {
		CouponEventAggregateSnapshotResult result = snapshotService.apply(eventId, snapshot);
		if (result == CouponEventAggregateSnapshotResult.UNREADABLE) {
			log.warn("최종 집계 스냅샷을 반영하지 못해 회차 상태를 진행시키지 않습니다. eventId={}", eventId);
			return false;
		}
		return true;
	}

	private boolean markSoldOut(Long eventId) {
		int updatedCount = couponEventRepository.markSoldOut(
				eventId, CouponEventStatus.OPEN, CouponEventStatus.SOLD_OUT);
		if (updatedCount > 0) {
			log.info("쿠폰 회차를 소진 처리했습니다. eventId={}", eventId);
			return true;
		}
		return false;
	}

	private boolean markClosed(Long eventId) {
		int updatedCount = couponEventRepository.markClosed(
				eventId, CLOSE_TARGET_STATUSES, CouponEventStatus.CLOSED);
		if (updatedCount > 0) {
			log.info("쿠폰 회차를 마감했습니다. eventId={}", eventId);
			return true;
		}
		return false;
	}

	private StatsRead readStats(Long eventId) {
		try {
			return new StatsRead(statsReader.read(eventId), false);
		} catch (DataAccessException | IllegalStateException exception) {
			log.warn("쿠폰 회차 현황을 읽지 못해 상태 전환을 미룹니다. eventId={}", eventId, exception);
			return new StatsRead(null, true);
		}
	}

	// snapshot == null 이면서 unreadable == false 가 Redis 상태 없음
	private record StatsRead(CouponEventStatsSnapshot snapshot, boolean unreadable) {
	}

	public record SweepResult(
			int soldOut,
			int closed,
			int waitingForDrain,
			int unreadable) {

		SweepResult merge(SweepResult other) {
			return new SweepResult(
					soldOut + other.soldOut,
					closed + other.closed,
					waitingForDrain + other.waitingForDrain,
					unreadable + other.unreadable);
		}
	}
}
