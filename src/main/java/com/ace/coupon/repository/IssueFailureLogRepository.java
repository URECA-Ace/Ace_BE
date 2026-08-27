package com.ace.coupon.repository;

import com.ace.coupon.entity.IssueFailureLog;
import com.ace.coupon.persistence.failure.IssueFailureStage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface IssueFailureLogRepository extends JpaRepository<IssueFailureLog, Long> {

	List<IssueFailureLog> findAllByRequestId(String requestId);

	// 재처리 대상 - 되살릴 수 있는 실패만 조회
	@Query("""
			SELECT failure
			FROM IssueFailureLog failure
			WHERE failure.failureStage = :stage
				AND failure.resolvedAt IS NULL
				AND failure.compensationResult IN :retryableResults
				AND failure.id > :lastSeenId
			ORDER BY failure.id
			""")
	List<IssueFailureLog> findRetryTargets(
			@Param("stage") IssueFailureStage stage,
			@Param("retryableResults") Collection<String> retryableResults,
			@Param("lastSeenId") Long lastSeenId,
			Pageable pageable);

	// 되살릴 수 없어 사람이 봐야 하는 건수
	@Query("""
			SELECT COUNT(failure)
			FROM IssueFailureLog failure
			WHERE failure.failureStage = :stage
				AND failure.resolvedAt IS NULL
				AND failure.compensationResult NOT IN :retryableResults
			""")
	long countUnrecoverable(
			@Param("stage") IssueFailureStage stage,
			@Param("retryableResults") Collection<String> retryableResults);

	// 미해소 확정 실패가 남아 있는 회차
	@Query("""
			SELECT DISTINCT failure.eventId
			FROM IssueFailureLog failure
			WHERE failure.failureStage = :stage
				AND failure.resolvedAt IS NULL
			ORDER BY failure.eventId
			""")
	List<Long> findBlockedEventIds(
			@Param("stage") IssueFailureStage stage,
			Pageable pageable);
}
