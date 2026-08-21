package com.ace.coupon.repository;

import com.ace.coupon.entity.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select coupon from Coupon coupon where coupon.id = :couponId")
	Optional<Coupon> findByIdForUpdate(@Param("couponId") Long couponId);
}
