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
		name = "coupon_issue",
		indexes = {
				@Index(name = "idx_coupon_issue_event_user_status_created",
						columnList = "event_id, user_id, status, created_at"),
				@Index(name = "idx_coupon_issue_status_valid_to_id",
						columnList = "status, valid_to, issue_id")
		},
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

	// Stream 원본 위치를 결정적 UUID로 변환하므로 표준 UUID 문자열 길이와 일치한다.
	@Column(name = "message_id", length = 36)
	private String messageId;


	public void use(LocalDateTime usedAt) {
		validateTransition(CouponIssueStatus.USED);
		this.status = CouponIssueStatus.USED;
		this.usedAt = usedAt;
		this.canceledAt = null;
	}

	// 사용 취소
	public void cancel(LocalDateTime canceledAt) {
		validateTransition(CouponIssueStatus.ISSUED);
		this.status = CouponIssueStatus.ISSUED;
		this.usedAt = null;
		this.canceledAt = canceledAt;
	}

	public void expire(LocalDateTime expiredAt) {
		validateTransition(CouponIssueStatus.EXPIRED);
		this.status = CouponIssueStatus.EXPIRED;
	}

	/**
	 * 재고 초과발급 복구 전용 전이. 일반 사용자 API가 쓰는 {@link CouponIssueStatus#allowedTransitions()}
	 * 상태머신과는 무관하게, ISSUED 상태의 건만 CANCELED로 되돌린다(슬롯 반납).
	 */
	public void revoke(LocalDateTime revokedAt) {
		if (this.status != CouponIssueStatus.ISSUED) {
			throw new IllegalStateException(
					String.format("상태 전이 불가: %s -> %s", this.status, CouponIssueStatus.CANCELED));
		}
		this.status = CouponIssueStatus.CANCELED;
		this.canceledAt = revokedAt;
	}

	/**
	 * StateMachineConsistencyCheck 복구 전용 전이. 손상된 coupon_history 이력을 삭제하고
	 * 원복할 때, 일반 상태머신 검증 없이 대상 상태로 되돌린다. 삭제 대상 범위에 USED/EXPIRED
	 * 전이가 없는지는 이 정책 호출 전에 이미 확인됐다는 전제이므로, 여기서는 별도 검증하지 않는다.
	 *
	 * 주의: 이 엔티티에서 상태머신 검증(validateTransition)을 완전히 우회하는 유일한 public
	 * 메서드다. 새 호출처를 추가하기 전에, 넘기는 target이 coupon_history의 최신 to_status와
	 * 반드시 일치하는지 호출부가 직접 보장해야 한다 — 어긋나면 CouponIssueHistoryStateConsistencyCheck가
	 * 위반으로 잡아내긴 하지만, 그건 사후 배치 탐지일 뿐 이 메서드 자체가 막아주는 게 아니다.
	 */
	public void restoreStatus(CouponIssueStatus target, LocalDateTime usedAt, LocalDateTime canceledAt) {
		this.status = target;
		this.usedAt = usedAt;
		this.canceledAt = canceledAt;
	}

	/**
	 * IssueHistoryTimeSyncConsistencyCheck 복구 전용: coupon_history의 실제 기록 시각으로 issued_at을 맞춘다.
	 * 값 검증 없는 단순 setter다 — 호출부(정책)가 이미 needsPatch로 걸러낸 값만 넘긴다는 전제이며,
	 * status는 건드리지 않는다. 새 호출처를 추가할 때는 대상 발급 건의 status가 이미 최신 history와
	 * 일치하는 상태인지(재사용 위험이 없는지) 반드시 직접 보장해야 한다.
	 */
	public void syncIssuedAt(LocalDateTime issuedAt) {
		this.issuedAt = issuedAt;
	}

	/** IssueHistoryTimeSyncConsistencyCheck 복구 전용: coupon_history의 실제 기록 시각으로 used_at을 맞춘다. 주의사항은 {@link #syncIssuedAt}과 동일하다. */
	public void syncUsedAt(LocalDateTime usedAt) {
		this.usedAt = usedAt;
	}

	private void validateTransition(CouponIssueStatus target) {
		if (!this.status.canTransitTo(target)) {
			throw new IllegalStateException(
					String.format("상태 전이 불가: %s -> %s", this.status, target));
		}
	}
}
