package com.ace.coupon.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.redis.CouponEventStatsSnapshot;
import com.ace.coupon.redis.RedisCouponEventStatsReader;
import com.ace.coupon.repository.CouponEventRepository;
import com.ace.event.coupon.CouponIssuanceCompletedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 회차 상태를 SOLD_OUT / CLOSED 로 진행시킴
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponEventLifecycleService {

	private static final int SWEEP_BATCH_SIZE = 100;

	// 한 번의 실행이 훑을 최대 페이지 수
	// 처리 불가 회차가 앞을 막아도 뒤쪽까지 도달해야 한다
	private static final int MAX_PAGES = 50;

	// Redis 현황을 확인해야 마감할 수 있는 상태
	private static final List<CouponEventStatus> DRAIN_REQUIRED_STATUSES = List.of(
			CouponEventStatus.OPEN,
			CouponEventStatus.SOLD_OUT);

	// 한 번도 열리지 못한 회차
	private static final List<CouponEventStatus> NEVER_OPENED_STATUSES = List.of(
			CouponEventStatus.SCHEDULED);

	private final CouponEventRepository couponEventRepository;
	private final RedisCouponEventStatsReader statsReader;
	private final CouponEventAggregateSnapshotService snapshotService;
	private final ApplicationEventPublisher eventPublisher;

	public SweepResult sweep() {
		return markDrainedEventsSoldOut()
				.merge(closeDrainedDueEvents())
				.merge(closeNeverOpenedEvents());
	}

	// 재고가 소진되고 저장까지 끝난 회차를 SOLD_OUT 으로 전환
	private SweepResult markDrainedEventsSoldOut() {
		int soldOut = 0;
		int waitingForDrain = 0;
		int unresolved = 0;

		long lastSeenId = 0L;
		for (int page = 0; page < MAX_PAGES; page++) {
			List<Long> eventIds = couponEventRepository.findSnapshotTargetEventIds(
					CouponEventStatus.OPEN, lastSeenId, PageRequest.of(0, SWEEP_BATCH_SIZE));
			if (eventIds.isEmpty()) {
				break;
			}

			for (Long eventId : eventIds) {
				StatsRead read = readStats(eventId);
				if (read.unreadable()) {
					unresolved++;
					continue;
				}
				if (read.snapshot() == null) {
					// Redis 상태가 없으면 재고 소진 여부를 알 수 없다
					unresolved++;
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
					eventPublisher.publishEvent(CouponIssuanceCompletedEvent.builder()
							.couponEventId(eventId)
							.completedAt(LocalDateTime.now())
							.build());
				}
			}

			lastSeenId = eventIds.get(eventIds.size() - 1);
			if (eventIds.size() < SWEEP_BATCH_SIZE) {
				break;
			}
		}
		return new SweepResult(soldOut, 0, waitingForDrain, unresolved);
	}

	// 마감 시각이 지나고 저장까지 끝난 회차를 CLOSED 로 전환
	// Redis 현황이 없는 경우 여기서는 마감하지 않는다.
	// 발급이 있었을 수 있는 회차라 초기화 실패나 키 유실과 구분되지 않고,
	// 그대로 마감하면 검증이 확정되지 않은 집계를 Drain 조건으로 신뢰하게 된다
	private SweepResult closeDrainedDueEvents() {
		int closed = 0;
		int waitingForDrain = 0;
		int unresolved = 0;

		long lastSeenId = 0L;
		for (int page = 0; page < MAX_PAGES; page++) {
			List<Long> eventIds = couponEventRepository.findCloseTargetEventIds(
					DRAIN_REQUIRED_STATUSES, lastSeenId, PageRequest.of(0, SWEEP_BATCH_SIZE));
			if (eventIds.isEmpty()) {
				break;
			}

			for (Long eventId : eventIds) {
				switch (closeIfDrained(eventId)) {
					case CLOSED -> closed++;
					case WAITING_FOR_DRAIN -> waitingForDrain++;
					case UNRESOLVED -> unresolved++;
				}
			}

			lastSeenId = eventIds.get(eventIds.size() - 1);
			if (eventIds.size() < SWEEP_BATCH_SIZE) {
				break;
			}
		}
		return new SweepResult(0, closed, waitingForDrain, unresolved);
	}

	/**
	 * 회차 하나를 마감 가능한지 확인하고, 가능하면 {@code CLOSED} 로 전환한다.
	 *
	 * <p>주기 sweep 과 수동 마감 API 가 모두 이 메서드를 통해서만 상태를 진행시킨다.
	 * 마감 판단이 한 곳에만 있어야 Drain 게이트를 우회하는 경로가 생기지 않는다.
	 */
	public CloseAttempt closeIfDrained(Long eventId) {
		StatsRead read = readStats(eventId);
		if (read.unreadable()) {
			return CloseAttempt.UNRESOLVED;
		}
		if (read.snapshot() == null) {
			log.warn("마감 대상 회차의 Redis 현황이 없어 마감을 보류합니다. eventId={}", eventId);
			return CloseAttempt.UNRESOLVED;
		}
		if (!isDrained(read.snapshot())) {
			// 재고는 다 나갔지만 아직 저장 중인 건이 있다. 지금 찍으면 확정 전 값으로 마감된다
			return CloseAttempt.WAITING_FOR_DRAIN;
		}
		if (applyFinalSnapshot(eventId, read.snapshot())
				&& markClosed(eventId, read.snapshot())) {
			return CloseAttempt.CLOSED;
		}
		return CloseAttempt.UNRESOLVED;
	}

	public enum CloseAttempt {
		/** 집계를 확정하고 CLOSED 로 전환했다. */
		CLOSED,
		/** 확정 대기 중인 발급 건이 남아 있어 마감을 미뤘다. */
		WAITING_FOR_DRAIN,
		/** Redis 현황을 읽지 못했거나 집계가 확정되지 않아 마감하지 못했다. */
		UNRESOLVED
	}

	// 오픈 스케쥴러가 멈춰 한 번도 열리지 못한 회차를 마감
	private SweepResult closeNeverOpenedEvents() {
		int closed = 0;

		long lastSeenId = 0L;
		for (int page = 0; page < MAX_PAGES; page++) {
			List<Long> eventIds = couponEventRepository.findCloseTargetEventIds(
					NEVER_OPENED_STATUSES, lastSeenId, PageRequest.of(0, SWEEP_BATCH_SIZE));
			if (eventIds.isEmpty()) {
				break;
			}

			for (Long eventId : eventIds) {
				int updatedCount = couponEventRepository.markScheduledClosed(
						eventId, CouponEventStatus.SCHEDULED, CouponEventStatus.CLOSED);
				if (updatedCount > 0) {
					log.info("한 번도 열리지 못한 쿠폰 회차를 마감했습니다. eventId={}", eventId);
					closed++;
				}
			}

			lastSeenId = eventIds.get(eventIds.size() - 1);
			if (eventIds.size() < SWEEP_BATCH_SIZE) {
				break;
			}
		}
		return new SweepResult(0, closed, 0, 0);
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
	// 반영이 거부된 경우(재고 설정 불일치, 확정 수 역행, 회차 없음)에도 상태를 진행시키면 안 된다
	private boolean applyFinalSnapshot(Long eventId, CouponEventStatsSnapshot snapshot) {
		CouponEventAggregateSnapshotResult result = snapshotService.apply(eventId, snapshot);
		if (!result.isAggregateFinalized()) {
			log.warn("최종 집계 스냅샷이 확정되지 않아 회차 상태를 진행시키지 않습니다. eventId={}, result={}",
					eventId, result);
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

	// 집계 세 컬럼이 이번 스냅샷과 일치할 때만 전환
	private boolean markClosed(Long eventId, CouponEventStatsSnapshot snapshot) {
		int updatedCount = couponEventRepository.markClosed(
				eventId,
				DRAIN_REQUIRED_STATUSES,
				CouponEventStatus.CLOSED,
				snapshot.totalStock().intValue(),
				snapshot.confirmedQuantity().intValue());
		if (updatedCount > 0) {
			log.info("쿠폰 회차를 마감했습니다. eventId={}", eventId);
			return true;
		}
		log.warn("집계가 최종 스냅샷과 달라 회차를 마감하지 않았습니다. eventId={}", eventId);
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
			int unresolved) {

		SweepResult merge(SweepResult other) {
			return new SweepResult(
					soldOut + other.soldOut,
					closed + other.closed,
					waitingForDrain + other.waitingForDrain,
					unresolved + other.unresolved);
		}
	}
}
