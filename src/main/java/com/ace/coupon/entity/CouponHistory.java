package com.ace.coupon.entity;

import com.ace.coupon.enums.CouponIssueStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
		name = "coupon_history",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_coupon_history_event_uid",
				columnNames = "event_uid"
		)
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class CouponHistory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "history_id")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "issue_id", nullable = false)
	private CouponIssue couponIssue;

	@Column(name = "from_status", length = 20)
	@Enumerated(EnumType.STRING)
	private CouponIssueStatus fromStatus;

	@Column(name = "to_status", nullable = false, length = 20)
	@Enumerated(EnumType.STRING)
	private CouponIssueStatus toStatus;

	@Column(name = "actor", length = 20)
	private String actor;

	@Column(name = "reason", length = 200)
	private String reason;

	@Column(name = "occurred_at", nullable = false)
	private LocalDateTime occurredAt;

	@Column(name = "recorded_at", nullable = false)
	private LocalDateTime recordedAt;

	@Column(name = "event_uid", length = 36)
	private String eventUid;
}
