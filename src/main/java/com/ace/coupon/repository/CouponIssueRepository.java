package com.ace.coupon.repository;

import com.ace.coupon.entity.CouponIssue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

	Optional<CouponIssue> findByRequestId(String requestId);

	Optional<CouponIssue> findByMessageId(String messageId);

	Optional<CouponIssue> findByCouponEvent_IdAndUser_Id(Long eventId, Long userId);

	Page<CouponIssue> findAllByUser_Id(Long userId, Pageable pageable);

	long countByCouponEvent_Id(Long eventId);
}
