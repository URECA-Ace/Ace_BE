package com.ace.coupon.entity;

import com.ace.coupon.enums.CouponIssueStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
		name = "coupon_state_idempotency",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_idempotency_event_uid",
				columnNames = "event_uid"
		)
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class CouponStateIdempotency {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "idempotency_id")
	private Long id;

	@Column(name = "event_uid", nullable = false, length = 36, updatable = false)
	private String eventUid;

	@Column(name = "issue_id", nullable = false, updatable = false)
	private Long issueId;

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Column(name = "target_status", nullable = false, length = 20, updatable = false)
	@Enumerated(EnumType.STRING)
	private CouponIssueStatus targetStatus;

	@Column(name = "from_status", length = 20)
	@Enumerated(EnumType.STRING)
	private CouponIssueStatus fromStatus;

	@Column(name = "event_id")
	private Long eventId;

	@Column(name = "occurred_at")
	private LocalDateTime occurredAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	public void complete(Long eventId, CouponIssueStatus fromStatus, LocalDateTime occurredAt) {
		this.eventId = eventId;
		this.fromStatus = fromStatus;
		this.occurredAt = occurredAt;
	}
}
