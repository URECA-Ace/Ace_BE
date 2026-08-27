package com.ace.coupon.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ace.coupon.entity.CouponIssue;

import jakarta.persistence.LockModeType;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

	Optional<CouponIssue> findByRequestId(String requestId);

	Optional<CouponIssue> findByMessageId(String messageId);

	Optional<CouponIssue> findByCouponEvent_IdAndUser_Id(Long eventId, Long userId);

	Page<CouponIssue> findAllByUser_Id(Long userId, Pageable pageable);

	long countByCouponEvent_Id(Long eventId);
	
	@Lock(LockModeType.PESSIMISTIC_WRITE) 
	@Query("SELECT ci FROM CouponIssue ci WHERE ci.id = :issueId")  
	Optional<CouponIssue> findByIdForUpdate(@Param("issueId") Long issueId);

	@Query("""
			SELECT ci FROM CouponIssue ci
			WHERE ci.status = 'ISSUED'
			  AND ci.validTo < :now
			  AND ci.id > :lastId
			ORDER BY ci.id ASC
			""")
	List<CouponIssue> findExpiredIssuesChunk(
			@Param("now") LocalDateTime now,
			@Param("lastId") Long lastId,
			Pageable pageable);
}
