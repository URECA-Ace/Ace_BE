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

	// 재처리 대상
	// 되살릴 수 있는 실패만 조회
	// 정렬은 마지막 시도 시각 오름차순
	// 단계를 목록으로 받는 이유: 한 단계만 조회하면 나머지 단계가 아무에게도 안 읽힌다
	@Query("""
			SELECT failure
			FROM IssueFailureLog failure
			WHERE failure.failureStage IN :stages
				AND failure.resolvedAt IS NULL
				AND failure.compensationResult IN :retryableResults
			ORDER BY failure.lastAttemptAt ASC, failure.id ASC
			""")
	List<IssueFailureLog> findRetryTargets(
			@Param("stages") Collection<IssueFailureStage> stages,
			@Param("retryableResults") Collection<String> retryableResults,
			Pageable pageable);

	// 되살릴 수 없어 사람이 봐야 하는 건수
	// settledResults 를 빼는 이유: 보상이나 확정에 성공해도 resolvedAt 이 안 찍히는 기록이 있다
	// 그대로 세면 이미 복구된 건이 영구히 회수 불가로 잡힌다
	@Query("""
			SELECT COUNT(failure)
			FROM IssueFailureLog failure
			WHERE failure.failureStage IN :stages
				AND failure.resolvedAt IS NULL
				AND failure.compensationResult NOT IN :retryableResults
				AND failure.compensationResult NOT IN :settledResults
			""")
	long countUnrecoverable(
			@Param("stages") Collection<IssueFailureStage> stages,
			@Param("retryableResults") Collection<String> retryableResults,
			@Param("settledResults") Collection<String> settledResults);

	// 미해소 실패가 남아 있는 회차
	// 재고가 돌아왔거나 확정이 끝난 값은 회차를 막지 않으므로 뺀다
	@Query("""
			SELECT DISTINCT failure.eventId
			FROM IssueFailureLog failure
			WHERE failure.failureStage IN :stages
				AND failure.resolvedAt IS NULL
				AND failure.compensationResult NOT IN :settledResults
			ORDER BY failure.eventId
			""")
	List<Long> findBlockedEventIds(
			@Param("stages") Collection<IssueFailureStage> stages,
			@Param("settledResults") Collection<String> settledResults,
			Pageable pageable);
}
