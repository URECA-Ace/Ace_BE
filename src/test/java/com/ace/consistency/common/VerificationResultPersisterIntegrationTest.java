package com.ace.consistency.common;

import com.ace.consistency.check.ConsistencyCheckIntegrationTestBase;
import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.entity.VerificationViolationEntity;
import com.ace.consistency.repository.VerificationViolationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerificationResultPersisterIntegrationTest extends ConsistencyCheckIntegrationTestBase {

	private static final String CHECK_PREFIX = "ViolationPersisterIntegration-";

	@Autowired VerificationResultPersister persister;
	@Autowired VerificationViolationRepository violationRepository;

	@AfterEach
	void cleanVerificationData() {
		jdbcTemplate.update("""
				DELETE vv FROM verification_violation vv
				JOIN verification_result vr ON vr.id = vv.verification_result_id
				WHERE vr.check_name LIKE :prefix
				""", new MapSqlParameterSource("prefix", CHECK_PREFIX + "%"));
		jdbcTemplate.update("DELETE FROM verification_violation WHERE batch_step_name LIKE :prefix",
				new MapSqlParameterSource("prefix", CHECK_PREFIX + "%"));
		jdbcTemplate.update("DELETE FROM verification_result WHERE check_name LIKE :prefix",
				new MapSqlParameterSource("prefix", CHECK_PREFIX + "%"));
	}

	@Test
	void 동기_실행은_요약과_위반_상세를_각각의_테이블에_저장한다() {
		String checkName = CHECK_PREFIX + generateUniqueId();
		VerificationResult result = failResult(checkName, Scope.ofEvent(100L), 1,
				List.of(violation(100L, "actual", 7)));

		VerificationResultEntity saved = persister.saveAndNotify(
				List.of(result), result.getScope(), result.getTriggerType()).getFirst();

		Map<String, Object> resultRow = jdbcTemplate.queryForMap("""
				SELECT violation_count, diff_detail FROM verification_result WHERE id = :id
				""", new MapSqlParameterSource("id", saved.getId()));
		assertThat(((Number) resultRow.get("violation_count")).intValue()).isEqualTo(1);
		assertThat(resultRow.get("diff_detail").toString()).contains("violationCount").doesNotContain("actual");

		List<VerificationViolationEntity> violations = violationRepository.findByVerificationResultId(saved.getId());
		assertThat(violations).singleElement().satisfies(violation -> {
			assertThat(violation.getTargetType()).isEqualTo(ViolationTargetType.EVENT);
			assertThat(violation.getTargetId()).isEqualTo(100L);
			assertThat(violation.getDetail()).containsEntry("actual", 7);
			assertThat(violation.getBatchJobInstanceId()).isNull();
			assertThat(violation.getBatchStepName()).isNull();
		});
	}

	@Test
	void 완료된_ALL_Step은_기존_임시_행을_결과에_연결한다() {
		String checkName = CHECK_PREFIX + generateUniqueId();
		long jobInstanceId = generateUniqueId();
		String stepName = CHECK_PREFIX + "Step-" + generateUniqueId();
		VerificationViolationEntity temporary = violationRepository.save(
				VerificationViolationEntity.forBatchStep(jobInstanceId, stepName, violation(200L, "actual", 3)));

		VerificationResultEntity saved = persister.saveStepResult(
				failResult(checkName, Scope.all(LocalDateTime.now()), 1, List.of()),
				jobInstanceId, stepName, false);

		Map<String, Object> linked = jdbcTemplate.queryForMap("""
				SELECT verification_result_id, batch_job_instance_id, batch_step_name
				FROM verification_violation WHERE id = :id
				""", new MapSqlParameterSource("id", temporary.getId()));
		assertThat(((Number) linked.get("verification_result_id")).longValue()).isEqualTo(saved.getId());
		assertThat(linked.get("batch_job_instance_id")).isNull();
		assertThat(linked.get("batch_step_name")).isNull();
	}

	@Test
	void 연결_건수_mismatch면_결과_저장과_연결을_모두_롤백한다() {
		String checkName = CHECK_PREFIX + generateUniqueId();

		assertThatThrownBy(() -> persister.saveStepResult(
				failResult(checkName, Scope.all(LocalDateTime.now()), 1, List.of()),
				generateUniqueId(), CHECK_PREFIX + "MissingStep", false))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("expected=1")
				.hasMessageContaining("linked=0");

		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM verification_result WHERE check_name = :checkName",
				new MapSqlParameterSource("checkName", checkName), Integer.class);
		assertThat(count).isZero();
	}

	@Test
	void 실패한_ALL_Step은_임시_행을_재시작용으로_보존한다() {
		String checkName = CHECK_PREFIX + generateUniqueId();
		long jobInstanceId = generateUniqueId();
		String stepName = CHECK_PREFIX + "FailedStep-" + generateUniqueId();
		VerificationViolationEntity temporary = violationRepository.save(
				VerificationViolationEntity.forBatchStep(jobInstanceId, stepName, violation(300L, "actual", 1)));

		persister.saveStepResult(VerificationResult.error(
				checkName, TriggerType.SCHEDULED, Scope.all(LocalDateTime.now()),
				new RuntimeException("failed"), LocalDateTime.now(), 1L),
				jobInstanceId, stepName, true);

		Map<String, Object> row = jdbcTemplate.queryForMap("""
				SELECT verification_result_id, batch_job_instance_id, batch_step_name
				FROM verification_violation WHERE id = :id
				""", new MapSqlParameterSource("id", temporary.getId()));
		assertThat(row.get("verification_result_id")).isNull();
		assertThat(((Number) row.get("batch_job_instance_id")).longValue()).isEqualTo(jobInstanceId);
		assertThat(row.get("batch_step_name")).isEqualTo(stepName);
	}

	private VerificationResult failResult(String checkName, Scope scope, int count,
										  List<ConsistencyCheck.Violation> violations) {
		return VerificationResult.fail(checkName, TriggerType.ON_DEMAND, scope, count,
				Map.of("violationCount", count), violations, LocalDateTime.now(), 1L);
	}

	private ConsistencyCheck.Violation violation(long targetId, String detailKey, int detailValue) {
		return new ConsistencyCheck.Violation(
				ViolationTargetType.EVENT, targetId, Map.of(detailKey, detailValue));
	}
}
