package com.ace.coupon.repository;

import com.ace.coupon.entity.CouponHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CouponHistoryRepository extends JpaRepository<CouponHistory, Long> {

	boolean existsByEventUid(String eventUid);

	List<CouponHistory> findAllByCouponIssue_IdOrderByOccurredAtAsc(Long issueId);
}
