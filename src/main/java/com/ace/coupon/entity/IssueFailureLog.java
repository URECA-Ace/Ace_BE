package com.ace.coupon.entity;

import com.ace.coupon.persistence.failure.IssueFailureStage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 발급 저장, 보상 실패 기록
// 저장이 실패한 건이라 code coupon_issue 행이 없을 수 있으므로 FK X
@Entity
@Table(
		name = "issue_failure_log",
		indexes = {
				@Index(name = "idx_issue_failure_log_request", columnList = "request_id"),
				@Index(name = "idx_issue_failure_log_event_stage", columnList = "event_id, failure_stage"),
				// 재처리 대상 조회용
				@Index(
						name = "idx_issue_failure_log_retry",
						columnList = "failure_stage, compensation_result, resolved_at, failure_id")
		}
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class IssueFailureLog {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "failure_id")
	private Long id;

	@Column(name = "event_id", nullable = false)
	private Long eventId;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "request_id", nullable = false, length = 36)
	private String requestId;

	// 판정 시 부여된 순번
	@Column(name = "issue_sequence")
	private Long issueSequence;

	@Column(name = "failure_stage", nullable = false, columnDefinition = "varchar(30)")
	@Enumerated(EnumType.STRING)
	private IssueFailureStage failureStage;

	// 보상 Lua 반환값(재고가 복구 되었는지 판단)
	@Column(name = "compensation_result", length = 30)
	private String compensationResult;

	// 개인정보가 섞이므로 MaskingUtil 을 통과한 값만 저장
	@Column(name = "error_message", columnDefinition = "TEXT")
	private String errorMessage;

	@Column(name = "incident_id", length = 36)
	private String incidentId;

	@Column(name = "occurred_at", nullable = false, columnDefinition = "datetime(6)")
	private LocalDateTime occurredAt;

	// 회수가 끝난 시각
	@Column(name = "resolved_at", columnDefinition = "datetime(6)")
	private LocalDateTime resolvedAt;

	public boolean isResolved() {
		return resolvedAt != null;
	}

	// 재확인 결과를 최신 판정으로 갱신
	// 갱신하지 않으면 최초 기록값이 남아 재처리기가 매 주기 같은 건을 다시 집는다
	public void updateConfirmResult(String confirmResult) {
		if (confirmResult == null || confirmResult.isBlank()) {
			throw new IllegalArgumentException("확정 판정 값이 필요합니다.");
		}
		this.compensationResult = confirmResult;
	}

	// 확정이 실제로 끝난 건만 해소로 표시
	public void resolve(LocalDateTime resolvedAt) {
		if (resolvedAt == null) {
			throw new IllegalArgumentException("해소 시각이 필요합니다.");
		}
		if (this.resolvedAt != null) {
			return;
		}
		this.resolvedAt = resolvedAt;
	}
}
