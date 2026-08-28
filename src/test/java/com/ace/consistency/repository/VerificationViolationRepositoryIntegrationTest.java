package com.ace.consistency.repository;

import com.ace.consistency.check.ConsistencyCheckIntegrationTestBase;
import com.ace.consistency.common.ConsistencyCheck.Violation;
import com.ace.consistency.common.ViolationTargetType;
import com.ace.consistency.entity.VerificationViolationEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationViolationRepositoryIntegrationTest extends ConsistencyCheckIntegrationTestBase {

	@Autowired
	private VerificationViolationRepository repository;

	@Test
	void 최근_heartbeat가_있는_Step의_행은_남기고_멈춘_Step과_메타데이터가_없는_행만_삭제한다() {
		long staleInstanceId = generateUniqueId();
		long activeInstanceId = generateUniqueId();
		long missingInstanceId = generateUniqueId();
		LocalDateTime threshold = LocalDateTime.now().minusMinutes(30);
		insertBatchExecution(staleInstanceId, threshold.minusMinutes(1));
		insertBatchExecution(activeInstanceId, threshold.plusMinutes(1));

		List<VerificationViolationEntity> saved = repository.saveAll(List.of(
				batchViolation(staleInstanceId, 810001L),
				batchViolation(activeInstanceId, 810002L),
				batchViolation(missingInstanceId, 810003L)));
		List<Long> ids = saved.stream().map(VerificationViolationEntity::getId).toList();

		int deleted = repository.deleteOrphansStaleSince(threshold);

		assertThat(deleted).isEqualTo(2);
		List<Map<String, Object>> remaining = jdbcTemplate.queryForList("""
				SELECT id, target_id FROM verification_violation WHERE id IN (:ids)
				""", new MapSqlParameterSource("ids", ids));
		assertThat(remaining).singleElement()
				.extracting(row -> ((Number) row.get("target_id")).longValue())
				.isEqualTo(810002L);

		jdbcTemplate.update("DELETE FROM verification_violation WHERE id IN (:ids)",
				new MapSqlParameterSource("ids", ids));
		deleteBatchExecution(staleInstanceId);
		deleteBatchExecution(activeInstanceId);
	}

	private VerificationViolationEntity batchViolation(long jobInstanceId, long targetId) {
		return VerificationViolationEntity.forBatchStep(jobInstanceId, "StockStep",
				new Violation(ViolationTargetType.EVENT, targetId, Map.of()));
	}

	private void insertBatchExecution(long jobInstanceId, LocalDateTime lastUpdated) {
		long jobExecutionId = jobInstanceId + 1_000_000_000L;
		long stepExecutionId = jobInstanceId + 2_000_000_000L;
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("instanceId", jobInstanceId)
				.addValue("jobExecutionId", jobExecutionId)
				.addValue("stepExecutionId", stepExecutionId)
				.addValue("jobKey", "key-" + jobInstanceId)
				.addValue("lastUpdated", lastUpdated);
		jdbcTemplate.update("""
				INSERT INTO BATCH_JOB_INSTANCE (JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY)
				VALUES (:instanceId, 0, 'cleanup-test', :jobKey)
				""", params);
		jdbcTemplate.update("""
				INSERT INTO BATCH_JOB_EXECUTION
				(JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID, CREATE_TIME, STATUS, LAST_UPDATED)
				VALUES (:jobExecutionId, 0, :instanceId, NOW(), 'FAILED', :lastUpdated)
				""", params);
		jdbcTemplate.update("""
				INSERT INTO BATCH_STEP_EXECUTION
				(STEP_EXECUTION_ID, VERSION, STEP_NAME, JOB_EXECUTION_ID, CREATE_TIME, STATUS, LAST_UPDATED)
				VALUES (:stepExecutionId, 0, 'StockStep', :jobExecutionId, NOW(), 'FAILED', :lastUpdated)
				""", params);
	}

	private void deleteBatchExecution(long jobInstanceId) {
		long jobExecutionId = jobInstanceId + 1_000_000_000L;
		jdbcTemplate.update("DELETE FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID = :id",
				new MapSqlParameterSource("id", jobExecutionId));
		jdbcTemplate.update("DELETE FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = :id",
				new MapSqlParameterSource("id", jobExecutionId));
		jdbcTemplate.update("DELETE FROM BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID = :id",
				new MapSqlParameterSource("id", jobInstanceId));
	}
}
