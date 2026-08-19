package com.ace.coupon.repository;

import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.enums.CouponEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
