package com.ace.consistency.entity;

import com.ace.consistency.common.DiffDetailConverter;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResult;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * verification_result 테이블 매핑 엔티티.
 *
 * Scope는 EVENT/AS_OF_RANGE/ALL에 따라 채워지는 필드가 다르므로,
 * "나중에 event_id로 필터링해서 조회" 같은 쿼리가 가능하도록
 * eventId, fromTime, toTime을 각각 nullable 컬럼으로 분리해서 저장한다
 * (Scope.toString() 문자열 하나로만 저장하면 나중에 조건 검색이 불가능해짐).
 */
@Entity
@Table(name = "verification_result", indexes = {
		@Index(name = "idx_vr_check_executed_at", columnList = "checkName, executedAt"),
		@Index(name = "idx_vr_event_id", columnList = "eventId"),
		@Index(name = "idx_vr_status", columnList = "status")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 스펙상 기본 생성자 필요, 외부에서 직접 생성은 막음
public class VerificationResultEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String checkName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private TriggerType triggerType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Scope.ScopeType scopeType;

	/** scopeType == EVENT 일 때만 값 존재 */
	@Column
	private Long eventId;

	/** scopeType == AS_OF_RANGE 일 때만 값 존재 */
	@Column
	private LocalDateTime scopeFrom;

	/** scopeType == AS_OF_RANGE 일 때만 값 존재 */
	@Column
	private LocalDateTime scopeTo;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private VerificationResult.Status status;

	/** FAIL일 때만 1 이상. PASS/ERROR는 0. 조회/정렬을 위해 diffDetail JSON과 별도로 컬럼화. */
	@Column(nullable = false)
	private int violationCount;

	@Convert(converter = DiffDetailConverter.class)
	@Column(columnDefinition = "JSON")
	private Map<String, Object> diffDetail;

	@Column(length = 500)
	private String errorMessage;

	@Column(nullable = false)
	private LocalDateTime executedAt;

	@Column(nullable = false)
	private long durationMillis;

	/** 이 결과(특히 FAIL)에 대해 복구가 시도됐는지, 그 결과가 어땠는지를 나타내는 캐시 값.
	 *  대시보드 목록 조회/필터링을 매번 recovery_result와 조인하지 않고 바로 할 수 있도록 별도 컬럼으로 둔다.
	 *  실제 복구 시도 이력(성공/실패 상세, 메시지)은 RecoveryResult 테이블에 별도로 쌓이고,
	 *  이 필드는 ConsistencyRecoveryDispatcher가 재검증을 마친 뒤에만 갱신한다. */
	public enum RecoveryStatus {
		NONE,
		RECOVERED,
		RECOVERY_FAILED
	}

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private RecoveryStatus recoveryStatus;

	@Builder
	private VerificationResultEntity(String checkName, TriggerType triggerType,
									 Scope.ScopeType scopeType,
									 Long eventId, LocalDateTime scopeFrom, LocalDateTime scopeTo,
									 VerificationResult.Status status, int violationCount,
									 Map<String, Object> diffDetail,
									 String errorMessage, LocalDateTime executedAt, long durationMillis,
									 RecoveryStatus recoveryStatus) {
		this.checkName = checkName;
		this.triggerType = triggerType;
		this.scopeType = scopeType;
		this.eventId = eventId;
		this.scopeFrom = scopeFrom;
		this.scopeTo = scopeTo;
		this.status = status;
		this.violationCount = violationCount;
		this.diffDetail = diffDetail;
		this.errorMessage = errorMessage;
		this.executedAt = executedAt;
		this.durationMillis = durationMillis;
		this.recoveryStatus = recoveryStatus;
	}

	/** 복구 정책 실행 후 재검증이 통과했을 때 호출한다. */
	public void markRecovered() {
		this.recoveryStatus = RecoveryStatus.RECOVERED;
	}

	/** 복구 정책 실행 후 재검증이 여전히 실패일 때 호출한다. */
	public void markRecoveryFailed() {
		this.recoveryStatus = RecoveryStatus.RECOVERY_FAILED;
	}

	/**
	 * VerificationResult(도메인 결과)로부터 Entity를 생성한다.
	 * Scope 타입에 따라 채워지는 값이 다르므로, 먼저 지역 변수로 계산한다.
	 */
	public static VerificationResultEntity from(VerificationResult result) {
		Scope scope = result.getScope();

		Long eventId = null;
		LocalDateTime scopeFrom = null;
		LocalDateTime scopeTo = null;

		switch (scope.getType()) {
			case EVENT -> eventId = scope.getEventId();
			case AS_OF_RANGE -> {
				scopeFrom = scope.getFrom();
				scopeTo = scope.getTo();
			}
			case ALL -> { /* 추가로 채울 값 없음 */ }
		}

		return builder()
				.checkName(result.getCheckName())
				.triggerType(result.getTriggerType())
				.scopeType(scope.getType())
				.eventId(eventId)
				.scopeFrom(scopeFrom)
				.scopeTo(scopeTo)
				.status(result.getStatus())
				.violationCount(result.getViolationCount())
				.diffDetail(result.getDiffDetail())
				.errorMessage(result.getErrorMessage())
				.executedAt(result.getExecutedAt())
				.durationMillis(result.getDurationMillis())
				.recoveryStatus(RecoveryStatus.NONE)
				.build();
	}

}