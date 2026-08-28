package com.ace.consistency.entity;

import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.DiffDetailConverter;
import com.ace.consistency.common.ViolationTargetType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * verification_violation 테이블 매핑 엔티티.
 *
 * VerificationResultEntity.diffDetail에 위반 건을 전부 나열하면(=sample) 위반이 대량으로
 * 발생했을 때 하나의 JSON 컬럼이 무한정 커지는 문제(ExecutionContext 비대화, MySQL filesort
 * 메모리 부족 등)가 있어, "위반 1건 = 행 1개"로 분리해 저장한다.
 *
 * ALL 스코프 배치는 Step마다 별도 트랜잭션으로 청크 단위 커밋되기 때문에, verification_result가
 * 아직 만들어지기 전에도 위반 행이 먼저 쌓일 수 있다. 그래서 verification_result가 생기기 전에는
 * stepExecutionId로 임시 태깅해두고, Step 종료 시점(ConsistencyStepCompletionListener)에
 * verificationResultId로 일괄 연결하거나, Step이 실패하면 일괄 삭제한다.
 * verificationResultId/stepExecutionId가 둘 다 nullable인 이유가 이 2단계(태깅 → 연결/삭제) 때문이다.
 */
@Entity
@Table(name = "verification_violation", indexes = {
		@Index(name = "idx_vv_verification_result_id", columnList = "verificationResultId"),
		@Index(name = "idx_vv_step_execution_id", columnList = "stepExecutionId"),
		@Index(name = "idx_vv_created_at", columnList = "createdAt")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 스펙상 기본 생성자 필요, 외부에서 직접 생성은 막음
public class VerificationViolationEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** ALL 스코프 배치에서 Step이 끝나 verification_result와 연결되기 전까지는 null. */
	@Column
	private Long verificationResultId;

	/** ALL 스코프 배치 경로에서만 값 존재 (연결/삭제 전 임시 태그). 그 외에는 null. */
	@Column
	private Long stepExecutionId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ViolationTargetType targetType;

	@Column(nullable = false)
	private Long targetId;

	@Convert(converter = DiffDetailConverter.class)
	@Column(columnDefinition = "JSON")
	private Map<String, Object> detail;

	@Column(nullable = false)
	private LocalDateTime createdAt;

	@Builder
	private VerificationViolationEntity(Long verificationResultId, Long stepExecutionId,
										ViolationTargetType targetType, Long targetId,
										Map<String, Object> detail, LocalDateTime createdAt) {
		this.verificationResultId = verificationResultId;
		this.stepExecutionId = stepExecutionId;
		this.targetType = targetType;
		this.targetId = targetId;
		this.detail = detail;
		this.createdAt = createdAt;
	}

	/** EVENT/AS_OF_RANGE 동기 경로: verification_result가 이미 저장되어 실제 id를 알고 있을 때 사용한다. */
	public static VerificationViolationEntity forResult(Long verificationResultId, ConsistencyCheck.Violation violation) {
		return builder()
				.verificationResultId(verificationResultId)
				.targetType(violation.getTargetType())
				.targetId(violation.getTargetId())
				.detail(violation.getDetail())
				.createdAt(LocalDateTime.now())
				.build();
	}

	/** ALL 스코프 배치 경로: verification_result가 아직 없어 stepExecutionId로 임시 태깅할 때 사용한다. */
	public static VerificationViolationEntity forStep(Long stepExecutionId, ConsistencyCheck.Violation violation) {
		return builder()
				.stepExecutionId(stepExecutionId)
				.targetType(violation.getTargetType())
				.targetId(violation.getTargetId())
				.detail(violation.getDetail())
				.createdAt(LocalDateTime.now())
				.build();
	}
}
