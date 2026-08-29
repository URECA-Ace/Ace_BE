package com.ace.coupon.repository;

import com.ace.coupon.entity.IssueFailureLog;
import com.ace.coupon.persistence.failure.IssueFailureStage;
import org.springframework.data.domain.Page;
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

	// 운영 조회 (DLQ 관제)
	// 상태(SETTLED / RETRYABLE / UNRECOVERABLE)마다 조건이 달라 쿼리를 나눈다
	// 판정에 쓰는 결과값 집합은 그룹(저장 / 확정)마다 다르므로 호출부가 넘긴다

	@Query("""
			SELECT failure
			FROM IssueFailureLog failure
			WHERE (:eventId IS NULL OR failure.eventId = :eventId)
				AND (:stage IS NULL OR failure.failureStage = :stage)
			""")
	Page<IssueFailureLog> findFiltered(
			@Param("eventId") Long eventId,
			@Param("stage") IssueFailureStage stage,
			Pageable pageable);

	@Query("""
			SELECT failure
			FROM IssueFailureLog failure
			WHERE (:eventId IS NULL OR failure.eventId = :eventId)
				AND (:stage IS NULL OR failure.failureStage = :stage)
				AND (failure.resolvedAt IS NOT NULL
					OR (failure.failureStage = :confirmStage
						AND failure.compensationResult IN :confirmSettled)
					OR (failure.failureStage <> :confirmStage
						AND failure.compensationResult IN :persistSettled))
			""")
	Page<IssueFailureLog> findSettled(
			@Param("eventId") Long eventId,
			@Param("stage") IssueFailureStage stage,
			@Param("confirmStage") IssueFailureStage confirmStage,
			@Param("confirmSettled") Collection<String> confirmSettled,
			@Param("persistSettled") Collection<String> persistSettled,
			Pageable pageable);

	@Query("""
			SELECT failure
			FROM IssueFailureLog failure
			WHERE (:eventId IS NULL OR failure.eventId = :eventId)
				AND (:stage IS NULL OR failure.failureStage = :stage)
				AND failure.resolvedAt IS NULL
				AND ((failure.failureStage = :confirmStage
						AND failure.compensationResult IN :confirmRetryable)
					OR (failure.failureStage <> :confirmStage
						AND failure.compensationResult IN :persistRetryable))
			""")
	Page<IssueFailureLog> findRetryable(
			@Param("eventId") Long eventId,
			@Param("stage") IssueFailureStage stage,
			@Param("confirmStage") IssueFailureStage confirmStage,
			@Param("confirmRetryable") Collection<String> confirmRetryable,
			@Param("persistRetryable") Collection<String> persistRetryable,
			Pageable pageable);

	// compensationResult 가 NULL 인 건도 사람이 봐야 하는 대상이다
	@Query("""
			SELECT failure
			FROM IssueFailureLog failure
			WHERE (:eventId IS NULL OR failure.eventId = :eventId)
				AND (:stage IS NULL OR failure.failureStage = :stage)
				AND failure.resolvedAt IS NULL
				AND (failure.compensationResult IS NULL
					OR (failure.failureStage = :confirmStage
						AND failure.compensationResult NOT IN :confirmKnown)
					OR (failure.failureStage <> :confirmStage
						AND failure.compensationResult NOT IN :persistKnown))
			""")
	Page<IssueFailureLog> findUnrecoverable(
			@Param("eventId") Long eventId,
			@Param("stage") IssueFailureStage stage,
			@Param("confirmStage") IssueFailureStage confirmStage,
			@Param("confirmKnown") Collection<String> confirmKnown,
			@Param("persistKnown") Collection<String> persistKnown,
			Pageable pageable);

	// 요약 집계 (그룹 단위)

	@Query("""
			SELECT COUNT(failure)
			FROM IssueFailureLog failure
			WHERE failure.failureStage IN :stages
				AND (failure.resolvedAt IS NOT NULL OR failure.compensationResult IN :settledResults)
			""")
	long countSettledInGroup(
			@Param("stages") Collection<IssueFailureStage> stages,
			@Param("settledResults") Collection<String> settledResults);

	@Query("""
			SELECT COUNT(failure)
			FROM IssueFailureLog failure
			WHERE failure.failureStage IN :stages
				AND failure.resolvedAt IS NULL
				AND failure.compensationResult IN :retryableResults
			""")
	long countRetryableInGroup(
			@Param("stages") Collection<IssueFailureStage> stages,
			@Param("retryableResults") Collection<String> retryableResults);

	@Query("""
			SELECT COUNT(failure)
			FROM IssueFailureLog failure
			WHERE failure.failureStage IN :stages
				AND failure.resolvedAt IS NULL
				AND (failure.compensationResult IS NULL
					OR failure.compensationResult NOT IN :knownResults)
			""")
	long countUnrecoverableInGroup(
			@Param("stages") Collection<IssueFailureStage> stages,
			@Param("knownResults") Collection<String> knownResults);
}
