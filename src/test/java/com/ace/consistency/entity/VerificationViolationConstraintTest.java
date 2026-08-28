package com.ace.consistency.entity;

import com.ace.consistency.check.ConsistencyCheckIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerificationViolationConstraintTest extends ConsistencyCheckIntegrationTestBase {

	@Test
	void 최종_소유와_배치_임시_소유_상태는_저장할_수_있다() {
		assertThatCode(() -> withForeignKeys(connection -> {
			long resultId = insertResult(connection);
			try (Statement statement = connection.createStatement()) {
				statement.executeUpdate(finalViolationSql(resultId));
				statement.executeUpdate(batchViolationSql());
				statement.executeUpdate("DELETE FROM verification_violation WHERE target_id IN (900001, 900002)");
				statement.executeUpdate("DELETE FROM verification_result WHERE id = " + resultId);
			}
			return null;
		})).doesNotThrowAnyException();
	}

	@Test
	void 결과와_배치_owner가_모두_없으면_CHECK_제약이_차단한다() {
		assertThatThrownBy(() -> withForeignKeys(connection -> {
			try (Statement statement = connection.createStatement()) {
				statement.executeUpdate("""
						INSERT INTO verification_violation
						(verification_result_id, batch_job_instance_id, batch_step_name,
						 target_type, target_id, detail, created_at)
						VALUES (NULL, NULL, NULL, 'EVENT', 900003, '{}', NOW())
						""");
			}
			return null;
		})).isInstanceOf(DataAccessException.class);
	}

	@Test
	void 존재하지_않는_verification_result_참조는_FK가_차단한다() {
		assertThatThrownBy(() -> withForeignKeys(connection -> {
			try (Statement statement = connection.createStatement()) {
				statement.executeUpdate(finalViolationSql(Long.MAX_VALUE));
			}
			return null;
		})).isInstanceOf(DataAccessException.class);
	}

	private <T> T withForeignKeys(ConnectionCallback<T> callback) {
		return jdbcTemplate.getJdbcTemplate().execute((ConnectionCallback<T>) connection -> {
			try (Statement statement = connection.createStatement()) {
				statement.execute("SET FOREIGN_KEY_CHECKS=1");
			}
			return callback.doInConnection(connection);
		});
	}

	private long insertResult(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.executeUpdate("""
					INSERT INTO verification_result
					(check_name, trigger_type, scope_type, status, violation_count, diff_detail,
					 executed_at, duration_millis, recovery_status)
					VALUES ('constraint-test', 'ON_DEMAND', 'ALL', 'FAIL', 1, '{}', NOW(), 1, 'NONE')
					""");
			try (ResultSet resultSet = statement.executeQuery("SELECT LAST_INSERT_ID()")) {
				resultSet.next();
				return resultSet.getLong(1);
			}
		}
	}

	private String finalViolationSql(long resultId) {
		return """
				INSERT INTO verification_violation
				(verification_result_id, batch_job_instance_id, batch_step_name,
				 target_type, target_id, detail, created_at)
				VALUES (%d, NULL, NULL, 'EVENT', 900001, '{}', NOW())
				""".formatted(resultId);
	}

	private String batchViolationSql() {
		return """
				INSERT INTO verification_violation
				(verification_result_id, batch_job_instance_id, batch_step_name,
				 target_type, target_id, detail, created_at)
				VALUES (NULL, 700001, 'ConstraintTestStep', 'EVENT', 900002, '{}', NOW())
				""";
	}
}
