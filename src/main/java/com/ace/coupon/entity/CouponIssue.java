package com.ace.coupon.entity;

import com.ace.coupon.enums.CouponIssueStatus;
import com.ace.user.entity.User;
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
		name = "coupon_issue",
		uniqueConstraints = {
				@UniqueConstraint(
						name = "uk_coupon_issue_event_user",
						columnNames = {"event_id", "user_id"}
				),
				@UniqueConstraint(
						name = "uk_coupon_issue_event_sequence",
						columnNames = {"event_id", "issue_sequence"}
				),
				@UniqueConstraint(
						name = "uk_coupon_issue_request_id",
						columnNames = "request_id"
				),
				@UniqueConstraint(
						name = "uk_coupon_issue_message_id",
						columnNames = "message_id"
				)
		}
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class CouponIssue {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "issue_id")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "event_id", nullable = false)
	private CouponEvent couponEvent;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Column(name = "issue_sequence", nullable = false)
	private Integer issueSequence;

	@Column(name = "request_id", nullable = false, length = 36)
	private String requestId;

	@Column(name = "status", nullable = false, length = 20)
	@Enumerated(EnumType.STRING)
	private CouponIssueStatus status;

	@Column(name = "issued_at", nullable = false)
	private LocalDateTime issuedAt;

	@Column(name = "valid_from", nullable = false)
	private LocalDateTime validFrom;

	@Column(name = "valid_to", nullable = false)
	private LocalDateTime validTo;

	@Column(name = "used_at")
	private LocalDateTime usedAt;

	@Column(name = "canceled_at")
	private LocalDateTime canceledAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "message_id", length = 36)
	private String messageId;
}
