package com.ace.coupon.service;

import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.redis.CouponEventStatsSnapshot;
import com.ace.coupon.redis.RedisCouponEventStatsReader;
import com.ace.coupon.repository.CouponEventRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// Redis가 들고 있는 확정 수를 coupon_event의 집계 컬럼으로 옮김
// 반영값은 판정 수(allocatedQuantity)가 아니라 확정 수(confirmedQuantity)
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponEventAggregateSnapshotService {

	private static final int SNAPSHOT_BATCH_SIZE = 100;

	// 한 번의 실행이 훑을 최대 페이지 수
	// 회차가 비정상적으로 많을 때 무한 순회를 막는다
	private static final int MAX_PAGES = 50;

	private final CouponEventRepository couponEventRepository;
	private final RedisCouponEventStatsReader statsReader;

	// 아직 발급 중인 회차의 집계 컬럼을 한 번씩 갱신
	// 마감 시각이 지난 회차는 대상이 아님
	// 페이지를 커서로 끝까지 넘긴다. 첫 페이지만 보면 앞의 회차가 계속 대상으로 남을 때 뒤쪽 회차가 영원히 갱신되지 않는다
	public SweepResult snapshotActiveEvents() {
		int applied = 0;
		int alreadyApplied = 0;
		int rejected = 0;
		int noRedisState = 0;
		int unreadable = 0;
		int scanned = 0;

		long lastSeenId = 0L;
		for (int page = 0; page < MAX_PAGES; page++) {
			List<Long> eventIds = couponEventRepository.findSnapshotTargetEventIds(
					CouponEventStatus.OPEN,
					lastSeenId,
					PageRequest.of(0, SNAPSHOT_BATCH_SIZE));
			if (eventIds.isEmpty()) {
				break;
			}

			for (Long eventId : eventIds) {
				scanned++;
				switch (snapshot(eventId)) {
					case APPLIED -> applied++;
					case ALREADY_APPLIED -> alreadyApplied++;
					case REJECTED -> rejected++;
					case NO_REDIS_STATE -> noRedisState++;
					case UNREADABLE -> unreadable++;
				}
			}

			lastSeenId = eventIds.get(eventIds.size() - 1);
			if (eventIds.size() < SNAPSHOT_BATCH_SIZE) {
				break;
			}
		}

		SweepResult result = new SweepResult(
				scanned, applied, alreadyApplied, rejected, noRedisState, unreadable);
		warnWhenAnomalyFound(result);
		return result;
	}

	// 회차 하나의 Redis 현황을 읽어 집계 컬럼에 반영
	public CouponEventAggregateSnapshotResult snapshot(Long eventId) {
		CouponEventStatsSnapshot snapshot;
		try {
			snapshot = statsReader.read(eventId);
		} catch (DataAccessException | IllegalStateException exception) {
			log.warn("쿠폰 회차 집계 스냅샷을 읽지 못했습니다. eventId={}", eventId, exception);
			return CouponEventAggregateSnapshotResult.UNREADABLE;
		}

		if (snapshot == null) {
			// 발급 중인 회차인데 Redis 판정 데이터가 없을 경우
			return CouponEventAggregateSnapshotResult.NO_REDIS_STATE;
		}
		return apply(eventId, snapshot);
	}

	// 읽어 둔 현황을 집계 컬럼에 반영
	// 이미 현황을 들고 있는 쪽이 다시 읽지 않도록 열어 둠
	public CouponEventAggregateSnapshotResult apply(Long eventId, CouponEventStatsSnapshot snapshot) {
		Long totalStock = snapshot.totalStock();
		Long confirmedQuantity = snapshot.confirmedQuantity();
		if (!isApplicable(eventId, totalStock, confirmedQuantity)) {
			return CouponEventAggregateSnapshotResult.UNREADABLE;
		}

		int updatedCount = couponEventRepository.applyAggregateSnapshot(
				eventId,
				totalStock.intValue(),
				confirmedQuantity.intValue());

		if (updatedCount == 0) {
			return classifyRejection(eventId, totalStock.intValue(), confirmedQuantity.intValue());
		}
		return CouponEventAggregateSnapshotResult.APPLIED;
	}

	// UPDATE 0행은 이미 같은 값과 반영 거부를 모두 포함
	private CouponEventAggregateSnapshotResult classifyRejection(
			Long eventId, int totalStock, int confirmedQuantity) {
		CouponEvent event = couponEventRepository.findById(eventId).orElse(null);
		if (event == null) {
			log.warn("집계를 반영할 쿠폰 회차가 없습니다. eventId={}", eventId);
			return CouponEventAggregateSnapshotResult.REJECTED;
		}

		boolean sameStock = event.getTotalStock() == totalStock;
		boolean sameAggregate = event.getIssuedQuantity() == confirmedQuantity
				&& event.getRemainingStock() == totalStock - confirmedQuantity;
		if (sameStock && sameAggregate) {
			return CouponEventAggregateSnapshotResult.ALREADY_APPLIED;
		}

		log.warn("쿠폰 회차 집계 반영이 거부됐습니다. "
						+ "eventId={}, redis(totalStock={}, confirmedQuantity={}), "
						+ "db(totalStock={}, issuedQuantity={}, remainingStock={})",
				eventId, totalStock, confirmedQuantity,
				event.getTotalStock(), event.getIssuedQuantity(), event.getRemainingStock());
		return CouponEventAggregateSnapshotResult.REJECTED;
	}

	private boolean isApplicable(Long eventId, Long totalStock, Long confirmedQuantity) {
		if (totalStock == null || confirmedQuantity == null) {
			log.warn("쿠폰 회차 집계 스냅샷 값이 비어 있습니다. eventId={}", eventId);
			return false;
		}
		if (totalStock <= 0
				|| totalStock > Integer.MAX_VALUE
				|| confirmedQuantity < 0
				|| confirmedQuantity > totalStock) {
			log.warn("쿠폰 회차 집계 스냅샷 값이 올바르지 않습니다. eventId={}, totalStock={}, confirmedQuantity={}",
					eventId, totalStock, confirmedQuantity);
			return false;
		}
		return true;
	}

	// 스킵을 조용히 넘기면 Redis 전면 장애가 "갱신할 게 없음"과 똑같이 보인다
	private void warnWhenAnomalyFound(SweepResult result) {
		if (!result.hasAnomaly()) {
			return;
		}
		log.warn("발급 중인 회차의 집계 스냅샷을 건너뛰었습니다. "
						+ "scanned={}, applied={}, rejected={}, noRedisState={}, unreadable={}",
				result.scanned(), result.applied(), result.rejected(),
				result.noRedisState(), result.unreadable());
	}

	// 한 번의 주기 실행 결과. 스킵 사유를 나눠 둬야 장애를 관측할 수 있다
	public record SweepResult(
			int scanned,
			int applied,
			int alreadyApplied,
			int rejected,
			int noRedisState,
			int unreadable) {

		public boolean hasAnomaly() {
			return rejected > 0 || noRedisState > 0 || unreadable > 0;
		}
	}
}
