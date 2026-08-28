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
		indexes = {
				@Index(
						name = "idx_coupon_event_status_open_at",
						columnList = "status, open_at"
				),
				@Index(
						name = "idx_coupon_event_status_close_at",
						columnList = "status, close_at"
				)
		},
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

	/**
	 * coupon_issue(원본)를 기준으로 집계한 실제 활성 발급 건수에 맞춰 캐시된 재고 카운터를 재계산한다.
	 * 정합성 복구(StockConsistencyRecoveryPolicy)에서만 사용하며, coupon_issue 자체는 건드리지 않는다.
	 */
	public void reconcileStock(int actualActiveCount, LocalDateTime reconciledAt) {
		this.issuedQuantity = actualActiveCount;
		this.remainingStock = this.totalStock - actualActiveCount;
		this.updatedAt = reconciledAt;
	}

	/**
	 * 재고 초과발급 회수 전용. 회수된 건수만큼 issued_quantity를 줄인다.
	 * 초과발급 상황에서는 remaining_stock이 "남은 자리"를 의미하지 않으므로 건드리지 않는다
	 * (remaining_stock을 포함한 전체 재계산은 초과분 회수가 끝난 뒤 {@link #reconcileStock}의 책임).
	 */
	public void releaseSlots(int releasedCount, LocalDateTime releasedAt) {
		this.issuedQuantity -= releasedCount;
		this.updatedAt = releasedAt;
	}
}
