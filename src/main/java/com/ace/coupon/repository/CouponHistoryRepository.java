package com.ace.coupon.repository;

import com.ace.coupon.entity.CouponHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CouponHistoryRepository extends JpaRepository<CouponHistory, Long> {

	    
	boolean existsByEventUid(String eventUid);
	
	Optional<CouponHistory> findByEventUid(String eventUid);   
	
	
	List<CouponHistory> findAllByCouponIssue_IdOrderByOccurredAtAsc(Long issueId);
}
