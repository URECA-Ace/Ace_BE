package com.ace.coupon.repository;

import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.enums.CouponEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CouponEventRepository extends JpaRepository<CouponEvent, Long> {

	Optional<CouponEvent> findByCoupon_IdAndRound(Long couponId, Integer round);

	List<CouponEvent> findAllByStatus(CouponEventStatus status);
}
