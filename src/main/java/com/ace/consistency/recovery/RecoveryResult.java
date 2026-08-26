package com.ace.consistency.recovery;

import java.time.LocalDateTime;
import java.util.Map;

import com.ace.consistency.common.DiffDetailConverter;
import com.ace.consistency.recovery.enums.RecoveryResultStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 복구 정책 실행 이력(recovery_result 테이블).
 * 하나의 VerificationResult에 대해 여러 번 복구를 시도할 수 있으므로 매 시도마다 한 행씩 쌓는다.
 * VerificationResultEntity.recoveryStatus는 이 중 가장 최근 시도 결과를 반영한 캐시 값이다.
 */
@Entity
@Table(name = "recovery_result", indexes = {
		@Index(name = "idx_rr_verification_result_id", columnList = "verificationResultId")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecoveryResult {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long verificationResultId;

	/** 복구 정책이 실제로 수정한 대상들. 예: {"issue_id": 4, "history_id": 3}. 체크마다 자유 형식. */
	@Convert(converter = DiffDetailConverter.class)
	@Column(columnDefinition = "JSON")
	private Map<String, Object> detail;

	/** 체크마다 자유롭게 채우는 결과 메시지. 예: "초과 발급된 3건에 대해서 복구완료했습니다." */
	@Column(length = 500)
	private String message;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private RecoveryResultStatus status;

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Builder
	private RecoveryResult(Long verificationResultId, Map<String, Object> detail, String message,
							RecoveryResultStatus status, LocalDateTime createdAt) {
		this.verificationResultId = verificationResultId;
		this.detail = detail;
		this.message = message;
		this.status = status;
		this.createdAt = createdAt;
	}

	public static RecoveryResult from(Long verificationResultId, RecoveryOutcome outcome, LocalDateTime createdAt) {
		return RecoveryResult.builder()
				.verificationResultId(verificationResultId)
				.detail(outcome.getDetail())
				.message(outcome.getMessage())
				.status(outcome.getStatus())
				.createdAt(createdAt)
				.build();
	}
}
