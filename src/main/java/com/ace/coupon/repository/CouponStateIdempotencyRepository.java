package com.ace.coupon.repository;

import com.ace.coupon.entity.CouponStateIdempotency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CouponStateIdempotencyRepository extends JpaRepository<CouponStateIdempotency, Long> {
	Optional<CouponStateIdempotency> findByEventUid(String eventUid);
}
