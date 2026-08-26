package com.ace.coupon.repository;

import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.enums.CampaignRedisInitializationStatus;
import com.ace.coupon.enums.CouponEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CouponEventRepository extends JpaRepository<CouponEvent, Long> {

	Optional<CouponEvent> findByCoupon_IdAndRound(Long couponId, Integer round);

	@Query("select e from CouponEvent e join fetch e.coupon where e.id = :eventId")
	Optional<CouponEvent> findWithCouponById(@Param("eventId") Long eventId);

	List<CouponEvent> findAllByStatus(CouponEventStatus status);

	@Query("""
			select event
			from CouponEvent event
			left join CampaignRedisInitialization initialization
			  on initialization.eventId = event.id
			where event.status in :statuses
			  and event.closeAt > :now
			  and (initialization.eventId is null or initialization.status <> :initializedStatus)
			order by coalesce(initialization.attemptCount, 0), event.id
			""")
	List<CouponEvent> findRedisInitializationRecoveryCandidates(
			@Param("statuses") List<CouponEventStatus> statuses,
			@Param("now") LocalDateTime now,
			@Param("initializedStatus") CampaignRedisInitializationStatus initializedStatus,
			Pageable pageable);

	// Stream 을 소비해야 할 회차
	@Query("select e.id from CouponEvent e where e.openAt <= :now and e.closeAt >= :since order by e.id")
	List<Long> findConsumableEventIds(
			@Param("now") LocalDateTime now,
			@Param("since") LocalDateTime since);

	/**
	 * 오픈 시각에 도달했고 아직 마감되지 않은 캠페인을 한 번의 조건부 UPDATE로 전환한다.
	 * 여러 인스턴스가 동시에 실행해도 SCHEDULED 상태인 행만 갱신되므로 전환은 멱등적이다.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE CouponEvent event
			SET event.status = :openStatus,
				event.updatedAt = CURRENT_TIMESTAMP
			WHERE event.status = :scheduledStatus
				AND event.openAt <= CURRENT_TIMESTAMP
				AND event.closeAt > CURRENT_TIMESTAMP
			""")
	int openDueEvents(
			@Param("scheduledStatus") CouponEventStatus scheduledStatus,
			@Param("openStatus") CouponEventStatus openStatus);

	// 주기 집계 스냅샷 대상 회차
	// 마감 시각이 지난 회차는 제외
	@Query("""
			SELECT event.id
			FROM CouponEvent event
			WHERE event.status = :status
				AND event.closeAt > CURRENT_TIMESTAMP
			ORDER BY event.id
			""")
	List<Long> findSnapshotTargetEventIds(
			@Param("status") CouponEventStatus status,
			Pageable pageable);

	// Redis가 갖고 있는 확정 수를 coupon_event 집계 컬럼에 반영
	// 값은 Redis에만 쌓고 주기적으로 이 조건부 UPDATE 한 번으로
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE CouponEvent event
			SET event.issuedQuantity = :confirmedQuantity,
				event.remainingStock = event.totalStock - :confirmedQuantity,
				event.updatedAt = CURRENT_TIMESTAMP
			WHERE event.id = :eventId
				AND event.totalStock = :totalStock
				AND event.issuedQuantity <= :confirmedQuantity
			""")
	int applyAggregateSnapshot(
			@Param("eventId") Long eventId,
			@Param("totalStock") Integer totalStock,
			@Param("confirmedQuantity") Integer confirmedQuantity);

	// 마감 대상 회차(마감 시각이 지났고 아직 CLOSED 가 아닌 회차)
	@Query("""
			SELECT event.id
			FROM CouponEvent event
			WHERE event.status IN :statuses
				AND event.closeAt <= CURRENT_TIMESTAMP
			ORDER BY event.id
			""")
	List<Long> findCloseTargetEventIds(
			@Param("statuses") List<CouponEventStatus> statuses,
			Pageable pageable);

	// 재고가 소진되고 파이프라인이 빈 회차를 SOLD_OUT 으로 전환
	// remainingStock = 0 조건은 최종 스냅샷이 실제로 반영됐는지를 DB 쪽에서 다시 확인
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE CouponEvent event
			SET event.status = :soldOutStatus,
				event.updatedAt = CURRENT_TIMESTAMP
			WHERE event.id = :eventId
				AND event.status = :openStatus
				AND event.remainingStock = 0
			""")
	int markSoldOut(
			@Param("eventId") Long eventId,
			@Param("openStatus") CouponEventStatus openStatus,
			@Param("soldOutStatus") CouponEventStatus soldOutStatus);

	// 마감 시각이 지난 회차를 CLOSED 로 전환
	// 이 상태값이 검증팀의 Drain 조건이라, 최종 스냅샷을 반영한 뒤에만 호출해야 한다
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE CouponEvent event
			SET event.status = :closedStatus,
				event.updatedAt = CURRENT_TIMESTAMP
			WHERE event.id = :eventId
				AND event.status IN :statuses
				AND event.closeAt <= CURRENT_TIMESTAMP
			""")
	int markClosed(
			@Param("eventId") Long eventId,
			@Param("statuses") List<CouponEventStatus> statuses,
			@Param("closedStatus") CouponEventStatus closedStatus);
}
