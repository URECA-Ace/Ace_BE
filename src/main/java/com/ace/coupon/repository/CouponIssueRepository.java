package com.ace.coupon.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ace.coupon.entity.CouponIssue;
import com.ace.coupon.enums.CouponIssueStatus;

import jakarta.persistence.LockModeType;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

	Optional<CouponIssue> findByRequestId(String requestId);

	Optional<CouponIssue> findByMessageId(String messageId);

	Optional<CouponIssue> findByCouponEvent_IdAndUser_Id(Long eventId, Long userId);

	Page<CouponIssue> findAllByUser_Id(Long userId, Pageable pageable);

	long countByCouponEvent_Id(Long eventId);

	long countByCouponEvent_IdAndStatusIn(Long eventId, List<CouponIssueStatus> statuses);

	@EntityGraph(attributePaths = "user")
	List<CouponIssue> findByCouponEvent_IdAndIssueSequenceGreaterThanOrderByIssueSequenceAsc(
			Long eventId, Integer issueSequence, Pageable pageable);

	// 재고 초과발급 회수 대상 선정용: 활성 발급 건을 발급 순번 최신순으로 조회
	List<CouponIssue> findByCouponEvent_IdAndStatusInOrderByIssueSequenceDesc(
			Long eventId, List<CouponIssueStatus> statuses);

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

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			UPDATE CouponIssue ci
			SET ci.status = 'EXPIRED'
			WHERE ci.id IN :ids
			  AND ci.status = 'ISSUED'
			""")
	int bulkExpire(@Param("ids") List<Long> ids);
}
