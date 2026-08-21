package com.ace.coupon.repository;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ace.coupon.entity.CampaignRedisInitialization;

public interface CampaignRedisInitializationRepository
		extends JpaRepository<CampaignRedisInitialization, Long> {

	@Modifying
	@Query(value = """
			INSERT INTO campaign_redis_initialization
			    (event_id, status, attempt_count, last_attempted_at, initialized_at,
			     last_error_code, last_error_message, updated_at)
			VALUES (:eventId, 'PENDING', 1, :attemptedAt, NULL, NULL, NULL, :attemptedAt)
			ON DUPLICATE KEY UPDATE
			    status = CASE WHEN status = 'INITIALIZED' THEN status ELSE 'PENDING' END,
			    attempt_count = attempt_count + 1,
			    last_attempted_at = VALUES(last_attempted_at),
			    last_error_code = CASE WHEN status = 'INITIALIZED' THEN last_error_code ELSE NULL END,
			    last_error_message = CASE WHEN status = 'INITIALIZED' THEN last_error_message ELSE NULL END,
			    updated_at = VALUES(updated_at)
			""", nativeQuery = true)
	int recordAttempt(
			@Param("eventId") Long eventId,
			@Param("attemptedAt") LocalDateTime attemptedAt);

	@Modifying
	@Query(value = """
			UPDATE campaign_redis_initialization
			SET status = 'INITIALIZED',
			    initialized_at = :initializedAt,
			    last_error_code = NULL,
			    last_error_message = NULL,
			    updated_at = :initializedAt
			WHERE event_id = :eventId
			""", nativeQuery = true)
	int recordSuccess(
			@Param("eventId") Long eventId,
			@Param("initializedAt") LocalDateTime initializedAt);

	@Modifying
	@Query(value = """
			UPDATE campaign_redis_initialization
			SET status = 'FAILED',
			    last_error_code = :errorCode,
			    last_error_message = :errorMessage,
			    updated_at = :failedAt
			WHERE event_id = :eventId
			  AND status <> 'INITIALIZED'
			""", nativeQuery = true)
	int recordFailure(
			@Param("eventId") Long eventId,
			@Param("errorCode") String errorCode,
			@Param("errorMessage") String errorMessage,
			@Param("failedAt") LocalDateTime failedAt);
}
