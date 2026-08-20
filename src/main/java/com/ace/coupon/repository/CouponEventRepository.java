package com.ace.coupon.repository;

import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.enums.CouponEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CouponEventRepository extends JpaRepository<CouponEvent, Long> {

	Optional<CouponEvent> findByCoupon_IdAndRound(Long couponId, Integer round);

	@Query("select e from CouponEvent e join fetch e.coupon where e.id = :eventId")
	Optional<CouponEvent> findWithCouponById(@Param("eventId") Long eventId);

	List<CouponEvent> findAllByStatus(CouponEventStatus status);

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
