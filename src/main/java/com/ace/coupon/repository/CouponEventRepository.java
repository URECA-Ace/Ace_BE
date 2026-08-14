package com.ace.coupon.repository;

import com.ace.coupon.entity.CouponEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CouponEventRepository extends JpaRepository<CouponEvent, Long> {
}
