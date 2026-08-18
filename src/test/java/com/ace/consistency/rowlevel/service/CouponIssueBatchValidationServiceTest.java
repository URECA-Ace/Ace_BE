package com.ace.consistency.rowlevel.service;

import com.ace.consistency.rowlevel.dto.BatchRowValidationRequest;
import com.ace.consistency.rowlevel.dto.CouponIssueRow;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CouponIssueBatchValidationServiceTest {

	private final CouponIssueBatchValidationService service = new CouponIssueBatchValidationService(
			mock(JdbcTemplate.class), new RowLevelValidationService());

	@Test
	void maxIssueId를_생략하면_DB의_현재_최댓값을_사용한다() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		when(jdbcTemplate.queryForObject("SELECT COALESCE(MAX(issue_id), 0) FROM coupon_issue", Long.class))
				.thenReturn(0L);
		CouponIssueBatchValidationService serviceWithDatabaseMax = new CouponIssueBatchValidationService(
				jdbcTemplate, new RowLevelValidationService());
		BatchRowValidationRequest request = new BatchRowValidationRequest(
				LocalDateTime.of(2026, 8, 13, 12, 0), null, 1_000, 10);

		var response = serviceWithDatabaseMax.validate(request);

		assertThat(response.maxIssueId()).isZero();
		assertThat(response.processedRows()).isZero();
	}

	@Test
	void 필수_DB_컬럼이_NULL이어도_매핑에서_중단하지_않고_검증으로_전달한다() throws Exception {
		ResultSet resultSet = mock(ResultSet.class);

		CouponIssueRow row = service.mapRow(resultSet);

		assertThat(row.issueId()).isNull();
		assertThat(row.issuedAt()).isNull();
		assertThat(row.validFrom()).isNull();
		assertThat(row.validTo()).isNull();
		assertThat(row.createdAt()).isNull();
	}

	@Test
	void 최대값을_초과한_pageSize는_거부한다() {
		BatchRowValidationRequest request = new BatchRowValidationRequest(
				LocalDateTime.of(2026, 8, 13, 12, 0), 1_000L, 10_001, 10);

		assertThatThrownBy(() -> service.validate(request))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("pageSize");
	}

	@Test
	void 최대값을_초과한_failureSampleLimit은_거부한다() {
		BatchRowValidationRequest request = new BatchRowValidationRequest(
				LocalDateTime.of(2026, 8, 13, 12, 0), 1_000L, 1_000, 1_001);

		assertThatThrownBy(() -> service.validate(request))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("failureSampleLimit");
	}
}
