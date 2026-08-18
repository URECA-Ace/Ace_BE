package com.ace.consistency.rowlevel.service;

import com.ace.consistency.rowlevel.domain.ValidationStatus;
import com.ace.consistency.rowlevel.dto.BatchRowValidationRequest;
import com.ace.consistency.rowlevel.dto.BatchRowValidationResponse;
import com.ace.consistency.rowlevel.dto.CouponIssueRow;
import com.ace.consistency.rowlevel.dto.RowValidationResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Service
public class CouponIssueBatchValidationService {

	private static final int DEFAULT_PAGE_SIZE = 1_000;
	private static final int MAX_PAGE_SIZE = 10_000;
	private static final int DEFAULT_FAILURE_SAMPLE_LIMIT = 100;
	private static final int MAX_FAILURE_SAMPLE_LIMIT = 1_000;

	private static final String PAGE_QUERY = """
			SELECT issue_id, event_id, user_id, issue_sequence, request_id, message_id,
			       status, issued_at, valid_from, valid_to, used_at, canceled_at, created_at
			FROM coupon_issue
			WHERE issue_id > ? AND issue_id <= ?
			ORDER BY issue_id
			LIMIT ?
			""";
	private static final String MAX_ISSUE_ID_QUERY = "SELECT COALESCE(MAX(issue_id), 0) FROM coupon_issue";

	private final JdbcTemplate jdbcTemplate;
	private final RowLevelValidationService rowValidationService;

	public CouponIssueBatchValidationService(JdbcTemplate jdbcTemplate,
			RowLevelValidationService rowValidationService) {
		this.jdbcTemplate = jdbcTemplate;
		this.rowValidationService = rowValidationService;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public BatchRowValidationResponse validate(BatchRowValidationRequest request) {
		validateRequest(request);
		int pageSize = valueOrDefault(request.pageSize(), DEFAULT_PAGE_SIZE);
		int sampleLimit = valueOrDefault(request.failureSampleLimit(), DEFAULT_FAILURE_SAMPLE_LIMIT);
		// 호출자가 상한을 주지 않은 경우 트랜잭션 시작 시점의 PK 최댓값을 고정한다.
		// 이후 새 발급 행이 들어와도 이번 검증 범위에는 포함하지 않아 같은 입력의 재현성을 유지한다.
		long maxIssueId = resolveMaxIssueId(request.maxIssueId());

		long lastIssueId = 0;
		long processedRows = 0;
		long passRows = 0;
		long failRows = 0;
		long warningRows = 0;
		List<RowValidationResponse> failureSamples = new ArrayList<>();

		// OFFSET은 뒤 페이지로 갈수록 DB가 건너뛸 행이 늘어난다. 300만 건 전체 검증에서는
		// 직전 PK 이후를 읽는 keyset 방식으로 메모리 사용량과 조회 비용을 일정하게 유지한다.
		while (lastIssueId < maxIssueId) {
			List<CouponIssueRow> page = findPage(lastIssueId, maxIssueId, pageSize);
			if (page.isEmpty()) {
				break;
			}

			for (CouponIssueRow row : page) {
				RowValidationResponse result = rowValidationService.validateIssue(row, request.snapshotAt());
				processedRows++;
				if (result.status() == ValidationStatus.FAIL) {
					failRows++;
					if (failureSamples.size() < sampleLimit) {
						failureSamples.add(result);
					}
				} else if (result.status() == ValidationStatus.WARNING) {
					warningRows++;
				} else {
					passRows++;
				}
			}
			lastIssueId = page.get(page.size() - 1).issueId();
		}

		return new BatchRowValidationResponse(
				"coupon_issue", request.snapshotAt(), maxIssueId, pageSize,
				processedRows, passRows, failRows, warningRows, List.copyOf(failureSamples));
	}

	private long resolveMaxIssueId(Long requestedMaxIssueId) {
		if (requestedMaxIssueId != null) {
			return requestedMaxIssueId;
		}
		Long maxIssueId = jdbcTemplate.queryForObject(MAX_ISSUE_ID_QUERY, Long.class);
		return maxIssueId == null ? 0L : maxIssueId;
	}

	private List<CouponIssueRow> findPage(long lastIssueId, long maxIssueId, int pageSize) {
		return jdbcTemplate.query(PAGE_QUERY,
				preparedStatement -> {
					preparedStatement.setLong(1, lastIssueId);
					preparedStatement.setLong(2, maxIssueId);
					preparedStatement.setInt(3, pageSize);
				},
				(resultSet, rowNum) -> mapRow(resultSet));
	}

	CouponIssueRow mapRow(ResultSet resultSet) throws SQLException {
		return new CouponIssueRow(
				resultSet.getObject("issue_id", Long.class),
				resultSet.getObject("event_id", Long.class),
				resultSet.getObject("user_id", Long.class),
				resultSet.getObject("issue_sequence", Integer.class),
				resultSet.getString("request_id"),
				resultSet.getString("message_id"),
				resultSet.getString("status"),
				toLocalDateTime(resultSet, "issued_at"),
				toLocalDateTime(resultSet, "valid_from"),
				toLocalDateTime(resultSet, "valid_to"),
				toLocalDateTime(resultSet, "used_at"),
				toLocalDateTime(resultSet, "canceled_at"),
				toLocalDateTime(resultSet, "created_at")
		);
	}

	private java.time.LocalDateTime toLocalDateTime(ResultSet resultSet, String column) throws SQLException {
		var timestamp = resultSet.getTimestamp(column);
		return timestamp == null ? null : timestamp.toLocalDateTime();
	}

	private int valueOrDefault(Integer value, int defaultValue) {
		return value == null ? defaultValue : value;
	}

	private void validateRequest(BatchRowValidationRequest request) {
		if (request == null || request.snapshotAt() == null) {
			throw new IllegalArgumentException("재현 가능한 검증을 위해 snapshotAt은 필수입니다.");
		}
		if (request.maxIssueId() != null && request.maxIssueId() <= 0) {
			throw new IllegalArgumentException("maxIssueId는 생략하거나 양수여야 합니다.");
		}
		if (request.pageSize() != null && request.pageSize() <= 0) {
			throw new IllegalArgumentException("pageSize는 양수여야 합니다.");
		}
		if (request.pageSize() != null && request.pageSize() > MAX_PAGE_SIZE) {
			throw new IllegalArgumentException("pageSize는 " + MAX_PAGE_SIZE + " 이하여야 합니다.");
		}
		if (request.failureSampleLimit() != null && request.failureSampleLimit() <= 0) {
			throw new IllegalArgumentException("failureSampleLimit는 양수여야 합니다.");
		}
		if (request.failureSampleLimit() != null && request.failureSampleLimit() > MAX_FAILURE_SAMPLE_LIMIT) {
			throw new IllegalArgumentException(
					"failureSampleLimit는 " + MAX_FAILURE_SAMPLE_LIMIT + " 이하여야 합니다.");
		}
	}
}
