package com.ace.coupon.entity;

import com.ace.coupon.enums.CouponEventStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
		name = "coupon_event",
		indexes = @Index(
				name = "idx_coupon_event_status_open_at",
				columnList = "status, open_at"
		),
		uniqueConstraints = @UniqueConstraint(
				name = "uk_coupon_event_coupon_round",
				columnNames = {"coupon_id", "round"}
		)
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class CouponEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "event_id")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "coupon_id", nullable = false)
	private Coupon coupon;

	@Column(name = "round", nullable = false)
	private Integer round;

	@Column(name = "open_at", nullable = false)
	private LocalDateTime openAt;

	@Column(name = "close_at", nullable = false)
	private LocalDateTime closeAt;

	@Column(name = "total_stock", nullable = false)
	private Integer totalStock;

	@Column(name = "remaining_stock", nullable = false)
	private Integer remainingStock;

	@Column(name = "issued_quantity", nullable = false)
	private Integer issuedQuantity;

	@Column(name = "per_user_limit", nullable = false)
	private Integer perUserLimit;

	@Column(name = "status", nullable = false, length = 20)
	@Enumerated(EnumType.STRING)
	private CouponEventStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;
}
