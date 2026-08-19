package com.ace.consistency.check;

import com.ace.consistency.common.ConsistencyCheck.CheckOutcome;
import com.ace.consistency.common.Scope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@Testcontainers
class RowLevelConsistencyCheckJdbcTest {

	@Container
	@ServiceConnection
	static final MySQLContainer MYSQL =
			new MySQLContainer(DockerImageName.parse("mysql:8.4"));

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private NamedParameterJdbcTemplate namedJdbcTemplate;

	@BeforeEach
	void setUpSchema() {
		jdbcTemplate.execute("DROP TABLE IF EXISTS coupon_history");
		jdbcTemplate.execute("DROP TABLE IF EXISTS coupon_issue");
		jdbcTemplate.execute("""
				CREATE TABLE coupon_issue (
				  issue_id BIGINT PRIMARY KEY AUTO_INCREMENT,
				  event_id BIGINT,
				  user_id BIGINT,
				  issue_sequence INT,
				  request_id VARCHAR(36),
				  status VARCHAR(20),
				  issued_at DATETIME(6),
				  valid_from DATETIME(6),
				  valid_to DATETIME(6),
				  used_at DATETIME(6),
				  canceled_at DATETIME(6),
				  created_at DATETIME(6),
				  message_id VARCHAR(36)
				)
				""");
		jdbcTemplate.execute("""
				CREATE TABLE coupon_history (
				  history_id BIGINT PRIMARY KEY AUTO_INCREMENT,
				  issue_id BIGINT,
				  from_status VARCHAR(20),
				  to_status VARCHAR(20),
				  occurred_at DATETIME(6),
				  recorded_at DATETIME(6)
				)
				""");
	}

	@Test
	void NULL_상태인_발급_행을_구조_위반으로_검출한다() {
		insertIssue(null, null, 1L);

		CheckOutcome outcome = new CouponIssueStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all());

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
	}

	@Test
	void UUID_형식의_message_id는_구조_검증을_통과한다() {
		insertIssue("ISSUED", null, 1L, "10000000-0000-0000-0000-000000000001");

		CheckOutcome outcome = new CouponIssueStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all());

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	void Redis_Stream_ID_형식의_message_id는_공통_UUID_계약_위반으로_검출한다() {
		insertIssue("ISSUED", null, 1L, "1723982400000-0");

		CheckOutcome outcome = new CouponIssueStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all());

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
		assertThat(outcome.getDiffDetail().get("sample").toString())
				.contains("INVALID_MESSAGE_ID_FORMAT");
	}

	@Test
	void 길이만_36자인_비_UUID_request_id는_구조_위반으로_검출한다() {
		long issueId = insertIssue("ISSUED", null, 1L);
		jdbcTemplate.update("UPDATE coupon_issue SET request_id = ? WHERE issue_id = ?",
				"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx", issueId);

		CheckOutcome outcome = new CouponIssueStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all());

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
		assertThat(outcome.getDiffDetail().get("sample").toString())
				.contains("INVALID_REQUEST_ID");
	}

	@Test
	void 현재_상태와_최신_이력_상태가_일치하면_통과한다() {
		long issueId = insertIssue("USED", LocalDateTime.of(2026, 8, 18, 11, 0), 1L);
		insertHistory(issueId, null, "ISSUED", LocalDateTime.of(2026, 8, 18, 10, 0));
		insertHistory(issueId, "ISSUED", "USED", LocalDateTime.of(2026, 8, 18, 11, 0));

		CheckOutcome outcome = new CouponIssueHistoryStateConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all());

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	void 현재_상태와_최신_이력_상태가_다르면_위반으로_검출한다() {
		long issueId = insertIssue("USED", LocalDateTime.of(2026, 8, 18, 11, 0), 1L);
		insertHistory(issueId, null, "ISSUED", LocalDateTime.of(2026, 8, 18, 10, 0));

		CheckOutcome outcome = new CouponIssueHistoryStateConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all());

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
		assertThat(outcome.getDiffDetail().get("sample").toString())
				.contains("LATEST_STATUS_MISMATCH");
	}

	@Test
	void 상태_이력이_없는_발급_건은_감사_이력_누락으로_검출한다() {
		insertIssue("ISSUED", null, 1L);

		CheckOutcome outcome = new CouponIssueHistoryStateConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all());

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
		assertThat(outcome.getDiffDetail().get("sample").toString())
				.contains("NO_HISTORY");
	}

	@Test
	void 현재_상태가_NULL이고_최신_이력이_있으면_상태_불일치로_검출한다() {
		long issueId = insertIssue(null, null, 1L);
		insertHistory(issueId, null, "ISSUED", LocalDateTime.of(2026, 8, 18, 10, 0));

		CheckOutcome outcome = new CouponIssueHistoryStateConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all());

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
	}

	@Test
	void 최신_이력_상태가_NULL이면_현재_상태와의_불일치로_검출한다() {
		long issueId = insertIssue("ISSUED", null, 1L);
		insertHistory(issueId, null, null, LocalDateTime.of(2026, 8, 18, 10, 0));

		CheckOutcome outcome = new CouponIssueHistoryStateConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all());

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
	}

	@Test
	void 다른_이벤트의_불일치는_EVENT_범위에서_제외한다() {
		long targetIssueId = insertIssue("ISSUED", null, 1L);
		insertHistory(targetIssueId, null, "ISSUED", LocalDateTime.of(2026, 8, 18, 10, 0));
		long otherIssueId = insertIssue("USED", LocalDateTime.of(2026, 8, 18, 11, 0), 2L);
		insertHistory(otherIssueId, null, "ISSUED", LocalDateTime.of(2026, 8, 18, 10, 0));

		CheckOutcome outcome = new CouponIssueHistoryStateConsistencyCheck(namedJdbcTemplate)
				.check(Scope.ofEvent(1L));

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	void 허용되지_않은_상태_전이_이력을_구조_위반으로_검출한다() {
		long issueId = insertIssue("USED", LocalDateTime.of(2026, 8, 18, 11, 0), 1L);
		insertHistory(issueId, "CANCELED", "USED", LocalDateTime.of(2026, 8, 18, 11, 0));

		CheckOutcome outcome = new CouponHistoryStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all());

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
		assertThat(outcome.getDiffDetail().get("sample").toString())
				.contains("INVALID_STATUS_TRANSITION");
	}

	@Test
	void 사용_취소에_따른_USED에서_ISSUED로의_복원을_허용한다() {
		long issueId = insertIssue("ISSUED", null, 1L);
		insertHistory(issueId, "USED", "ISSUED", LocalDateTime.of(2026, 8, 18, 11, 0));

		CheckOutcome outcome = new CouponHistoryStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all());

		assertThat(outcome.isPass()).isTrue();
		assertThat(outcome.getViolationCount()).isZero();
	}

	@Test
	void 발급_내역이_없는_고아_이력을_ALL_구조_위반으로_검출한다() {
		insertHistory(999L, null, "ISSUED", LocalDateTime.of(2026, 8, 18, 10, 0));

		CheckOutcome outcome = new CouponHistoryStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all());

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
		assertThat(outcome.getDiffDetail().get("sample").toString())
				.contains("ORPHAN_HISTORY");
	}

	@Test
	void 만료_후_사용된_행을_만료_시차_위반으로_검출한다() {
		insertIssue("USED", LocalDateTime.of(2026, 8, 18, 11, 0), 1L);
		Scope scope = Scope.ofAsOfRange(
				LocalDateTime.of(2026, 8, 18, 10, 30),
				LocalDateTime.of(2026, 8, 18, 12, 0));

		CheckOutcome outcome = new CouponExpirationLagConsistencyCheck(namedJdbcTemplate).check(scope);

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
		assertThat(outcome.getDiffDetail().get("sample").toString())
				.contains("USED_AFTER_EXPIRATION");
	}

	@Test
	void 검증_기준_시각_이후의_사용은_현재_스냅샷에서_제외한다() {
		insertIssue("USED", LocalDateTime.of(2026, 8, 18, 12, 30), 1L);
		Scope scope = Scope.ofAsOfRange(
				LocalDateTime.of(2026, 8, 18, 10, 30),
				LocalDateTime.of(2026, 8, 18, 12, 0));

		CheckOutcome outcome = new CouponExpirationLagConsistencyCheck(namedJdbcTemplate).check(scope);

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	void 검증_기준_시각_이후에_생성된_발급_건은_과거_만료_스냅샷에서_제외한다() {
		long issueId = insertIssue("ISSUED", null, 1L);
		jdbcTemplate.update("UPDATE coupon_issue SET created_at = ? WHERE issue_id = ?",
				Timestamp.valueOf(LocalDateTime.of(2026, 8, 18, 12, 30)), issueId);
		Scope scope = Scope.ofAsOfRange(
				LocalDateTime.of(2026, 8, 18, 10, 30),
				LocalDateTime.of(2026, 8, 18, 12, 0));

		CheckOutcome outcome = new CouponExpirationLagConsistencyCheck(namedJdbcTemplate).check(scope);

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	void 허용_경계를_넘긴_ISSUED_행을_만료_지연으로_검출한다() {
		insertIssue("ISSUED", null, 1L);
		Scope scope = Scope.ofAsOfRange(
				LocalDateTime.of(2026, 8, 18, 10, 30),
				LocalDateTime.of(2026, 8, 18, 12, 0));

		CheckOutcome outcome = new CouponExpirationLagConsistencyCheck(namedJdbcTemplate).check(scope);

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
		assertThat(outcome.getDiffDetail().get("sample").toString())
				.contains("EXPIRATION_BATCH_DELAY");
	}

	private long insertIssue(String status, LocalDateTime usedAt, long eventId) {
		return insertIssue(status, usedAt, eventId,
				"10000000-0000-0000-0000-%012d".formatted(eventId));
	}

	private long insertIssue(String status, LocalDateTime usedAt, long eventId, String messageId) {
		LocalDateTime issuedAt = LocalDateTime.of(2026, 8, 18, 9, 0);
		LocalDateTime validTo = LocalDateTime.of(2026, 8, 18, 10, 30);
		jdbcTemplate.update("""
				INSERT INTO coupon_issue (
				  event_id, user_id, issue_sequence, request_id, status,
				  issued_at, valid_from, valid_to, used_at, canceled_at, created_at, message_id
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)
				""",
				eventId, eventId, 1,
				"00000000-0000-0000-0000-00000000000" + eventId,
				status,
				Timestamp.valueOf(issuedAt), Timestamp.valueOf(issuedAt), Timestamp.valueOf(validTo),
				usedAt == null ? null : Timestamp.valueOf(usedAt),
				Timestamp.valueOf(issuedAt),
				messageId);
		return jdbcTemplate.queryForObject("SELECT MAX(issue_id) FROM coupon_issue", Long.class);
	}

	private void insertHistory(long issueId, String fromStatus, String toStatus, LocalDateTime occurredAt) {
		jdbcTemplate.update("""
				INSERT INTO coupon_history (
				  issue_id, from_status, to_status, occurred_at, recorded_at
				) VALUES (?, ?, ?, ?, ?)
				""",
				issueId, fromStatus, toStatus,
				Timestamp.valueOf(occurredAt), Timestamp.valueOf(occurredAt));
	}
}
