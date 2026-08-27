package com.ace.coupon.entity;

import java.time.LocalDateTime;

import com.ace.coupon.enums.CampaignRedisInitializationStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "campaign_redis_initialization")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class CampaignRedisInitialization {

	@Id
	@Column(name = "event_id")
	private Long eventId;

	@Column(name = "status", nullable = false, length = 20)
	@Enumerated(EnumType.STRING)
	private CampaignRedisInitializationStatus status;

	@Column(name = "attempt_count", nullable = false)
	private Integer attemptCount;

	@Column(name = "last_attempted_at")
	private LocalDateTime lastAttemptedAt;

	@Column(name = "initialized_at")
	private LocalDateTime initializedAt;

	@Column(name = "last_error_code", length = 60)
	private String lastErrorCode;

	@Column(name = "last_error_message", length = 500)
	private String lastErrorMessage;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	public static CampaignRedisInitialization pending(Long eventId, LocalDateTime now) {
		return CampaignRedisInitialization.builder()
				.eventId(eventId)
				.status(CampaignRedisInitializationStatus.PENDING)
				.attemptCount(0)
				.updatedAt(now)
				.build();
	}
}
