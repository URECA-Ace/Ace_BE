package com.ace.consistency.repository;

import com.ace.consistency.entity.VerificationViolationEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * VerificationViolationEntity(verification_violation, 위반 1건 = 행 1개) 영속화 담당.
 */
@Repository
public interface VerificationViolationRepository extends JpaRepository<VerificationViolationEntity, Long> {

	/** 복구 정책이 실제 위반 대상 전체(예: 이벤트 목록)를 복원할 때 사용한다. 표본이 아닌 전체 목록이어야 한다. */
	List<VerificationViolationEntity> findByVerificationResultId(Long verificationResultId);

	/** 화면 표시용으로 최근 N건만 필요할 때 사용한다. */
	List<VerificationViolationEntity> findByVerificationResultIdOrderByIdDesc(Long verificationResultId, Pageable pageable);

	/** ALL 스코프 배치 Step 성공 시, 재시작 전후에 누적된 행 전체를 실제 결과에 연결한다. */
	@Modifying
	@Query("""
			UPDATE VerificationViolationEntity v
			SET v.verificationResultId = :verificationResultId,
				v.batchJobInstanceId = null,
				v.batchStepName = null
			WHERE v.batchJobInstanceId = :jobInstanceId
			  AND v.batchStepName = :stepName
			""")
	int linkToResult(@Param("jobInstanceId") Long jobInstanceId,
					 @Param("stepName") String stepName,
					  @Param("verificationResultId") Long verificationResultId);

	/**
	 * 연결도 삭제도 되지 못한 채 남은 고아 행을 정리한다 (Step 종료 리스너가 실행되지 못한
	 * 이상 종료 등에 대비한 안전망). verificationResultId가 여전히 null인 행 중, 그 행을 태깅한
	 * Step의 BATCH_STEP_EXECUTION.LAST_UPDATED가 threshold보다 오래된 행만 대상으로 한다.
	 *
	 * 행 자체의 createdAt이 아니라 Step의 LAST_UPDATED를 기준으로 삼는 이유: LAST_UPDATED는
	 * Step이 청크를 커밋할 때마다 갱신되는 heartbeat라, ALL 스코프 Step이 threshold보다 오래
	 * 걸려도 계속 진행 중이면 값이 최신으로 유지된다. createdAt 기준이었다면 정상 진행 중인
	 * 느린 Step의 초반 위반 행이 아직 살아있는 Step인데도 고아로 오판되어 삭제될 수 있었다.
	 * BATCH_STEP_EXECUTION은 JPA 엔티티로 매핑되어 있지 않아 native query로 조인한다.
	 *
	 * OrphanViolationCleanupScheduler가 트랜잭션 없이 직접 호출하므로, 이 메서드 자체에
	 * @Transactional을 붙여둔다. @Modifying 쿼리는 커스텀 쿼리 메서드라 SimpleJpaRepository의
	 * 기본 트랜잭션(enableDefaultTransactions)이 적용되지 않아, 호출부에 트랜잭션이 없으면
	 * TransactionRequiredException이 발생한다.
	 */
	@Transactional
	@Modifying
	@Query(value = """
			DELETE v FROM verification_violation v
			WHERE v.verification_result_id IS NULL
			  AND NOT EXISTS (
			      SELECT 1
			      FROM BATCH_STEP_EXECUTION se
			      JOIN BATCH_JOB_EXECUTION je
			        ON je.JOB_EXECUTION_ID = se.JOB_EXECUTION_ID
			      WHERE je.JOB_INSTANCE_ID = v.batch_job_instance_id
			        AND se.STEP_NAME = v.batch_step_name
			        AND se.LAST_UPDATED >= :threshold
			  )
			""", nativeQuery = true)
	int deleteOrphansStaleSince(@Param("threshold") LocalDateTime threshold);
}
