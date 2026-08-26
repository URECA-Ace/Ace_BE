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

// Redis가 들고 있는 확정 수를 coupon_event의 집계 컬럼으로 옮김
// 반영값은 판정 수(allocatedQuantity)가 아니라 확정 수(confirmedQuantity)
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponEventAggregateSnapshotService {

	private static final int SNAPSHOT_BATCH_SIZE = 100;

	private final CouponEventRepository couponEventRepository;
	private final RedisCouponEventStatsReader statsReader;

	// 아직 발급 중인 회차의 집계 컬럼을 한 번씩 갱신
	// 마감 시각이 지난 회차는 대상이 아님
	public SweepResult snapshotActiveEvents() {
		List<Long> eventIds = couponEventRepository.findSnapshotTargetEventIds(
				CouponEventStatus.OPEN,
				PageRequest.of(0, SNAPSHOT_BATCH_SIZE));

		int applied = 0;
		int notModified = 0;
		int noRedisState = 0;
		int unreadable = 0;
		for (Long eventId : eventIds) {
			switch (snapshot(eventId)) {
				case APPLIED -> applied++;
				case NOT_MODIFIED -> notModified++;
				case NO_REDIS_STATE -> noRedisState++;
				case UNREADABLE -> unreadable++;
			}
		}

		SweepResult result = new SweepResult(
				eventIds.size(), applied, notModified, noRedisState, unreadable);
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
			// 재고 설정이 다르거나(다른 회차의 Redis 값) 이미 더 큰 확정 수가 반영된 경우다
			log.debug("쿠폰 회차 집계 스냅샷이 반영되지 않았습니다. eventId={}, confirmedQuantity={}",
					eventId, confirmedQuantity);
			return CouponEventAggregateSnapshotResult.NOT_MODIFIED;
		}
		return CouponEventAggregateSnapshotResult.APPLIED;
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
						+ "scanned={}, applied={}, noRedisState={}, unreadable={}",
				result.scanned(), result.applied(), result.noRedisState(), result.unreadable());
	}

	// 한 번의 주기 실행 결과. 스킵 사유를 나눠 둬야 장애를 관측할 수 있다
	public record SweepResult(
			int scanned,
			int applied,
			int notModified,
			int noRedisState,
			int unreadable) {

		public boolean hasAnomaly() {
			return noRedisState > 0 || unreadable > 0;
		}
	}
}
