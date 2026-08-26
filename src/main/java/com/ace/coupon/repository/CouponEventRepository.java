package com.ace.coupon.repository;

import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.enums.CampaignRedisInitializationStatus;
import com.ace.coupon.enums.CouponEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CouponEventRepository extends JpaRepository<CouponEvent, Long> {

	Optional<CouponEvent> findByCoupon_IdAndRound(Long couponId, Integer round);

	List<CouponEvent> findAllByCoupon_Id(Long couponId);

	long countByCoupon_Id(Long couponId);

	Optional<CouponEvent> findFirstByCoupon_IdAndTotalStockAndOpenAtAndCloseAtAndPerUserLimitOrderByIdAsc(
			Long couponId,
			Integer totalStock,
			LocalDateTime openAt,
			LocalDateTime closeAt,
			Integer perUserLimit);

	@Query("select coalesce(max(event.round), 0) from CouponEvent event where event.coupon.id = :couponId")
	Integer findMaxRoundByCouponId(@Param("couponId") Long couponId);

	@Query("select e from CouponEvent e join fetch e.coupon where e.id = :eventId")
	Optional<CouponEvent> findWithCouponById(@Param("eventId") Long eventId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select event from CouponEvent event where event.id = :eventId")
	Optional<CouponEvent> findByIdForUpdate(@Param("eventId") Long eventId);

	@Query("""
			select event
			from CouponEvent event
			join fetch event.coupon
			order by event.createdAt desc, event.id desc
			""")
	List<CouponEvent> findRecentWithCoupon(Pageable pageable);

	@Query("""
			select event
			from CouponEvent event
			join fetch event.coupon
			where event.status = :status
			order by event.createdAt desc, event.id desc
			""")
	List<CouponEvent> findRecentWithCouponByStatus(
			@Param("status") CouponEventStatus status,
			Pageable pageable);

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
}
