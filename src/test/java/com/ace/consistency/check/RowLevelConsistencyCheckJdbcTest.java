package com.ace.consistency.check;

import com.ace.consistency.common.ConsistencyCheck.CheckOutcome;
import com.ace.consistency.common.DiffDetailConverter;
import com.ace.consistency.common.Scope;
import com.ace.coupon.persistence.IssueRecord;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@Testcontainers
class RowLevelConsistencyCheckJdbcTest {
	private static final long TEST_ALLOWED_EXPIRATION_DELAY_MS = 30 * 60 * 1_000L;
	private static final Clock TEST_CLOCK = Clock.fixed(
			Instant.parse("2026-08-18T03:00:00Z"), ZoneId.of("Asia/Seoul"));
	private static final LocalDateTime TEST_CHECKED_AT =
			LocalDateTime.of(2026, 8, 18, 12, 0);

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
				.check(Scope.all(TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
		assertThat(firstSample(outcome))
				.containsEntry("candidate_recovery_action", null)
				.containsEntry("manual_review_required", true);
	}

	@Test
	void 발급_구조_ALL_검증은_현재_페이지의_이벤트만_검사한다() {
		insertIssue("ISSUED", null, 1L);
		insertIssue(null, null, 2L);

		CheckOutcome outcome = new CouponIssueStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all(List.of(1L), TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	void 발급_구조_ALL_검증은_to_이후에_생성된_행을_제외한다() {
		long issueId = insertIssue(null, null, 1L);
		jdbcTemplate.update("UPDATE coupon_issue SET created_at = ? WHERE issue_id = ?",
				Timestamp.valueOf(TEST_CHECKED_AT.plusMinutes(1)), issueId);

		CheckOutcome outcome = new CouponIssueStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all(List.of(1L), TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	void 위반_건수와_샘플은_제한없이_전부_계산된다() {
		for (int index = 0; index < 25; index++) {
			insertIssue(null, null, 1L);
		}

		CheckOutcome outcome = new CouponIssueStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all(TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(25);
		assertThat((List<?>) outcome.getDiffDetail().get("sample")).hasSize(25);
	}

	@Test
	void 이전_campaignId_Stream_Entry_ID_message_id는_계약_위반으로_검출한다() {
		insertIssue("ISSUED", null, 1L, "1-1755000000000-0");

		CheckOutcome outcome = new CouponIssueStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all(TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getDiffDetail().get("sample").toString())
				.contains("INVALID_MESSAGE_ID_FORMAT");
	}

	@Test
	void campaignId가_없는_Redis_Stream_ID는_message_id_계약_위반으로_검출한다() {
		insertIssue("ISSUED", null, 1L, "1723982400000-0");

		CheckOutcome outcome = new CouponIssueStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all(TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
		assertThat(outcome.getDiffDetail().get("sample").toString())
				.contains("INVALID_MESSAGE_ID_FORMAT");
	}

	@Test
	void PR26_결정적_UUID_message_id는_구조_검증을_통과한다() {
		String messageId = IssueRecord.messageId(1L, "1755000000000-0");
		insertIssue("ISSUED", null, 1L, messageId);

		CheckOutcome outcome = new CouponIssueStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all(TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	void SYNC_경로의_null_message_id는_구조_검증을_통과한다() {
		insertIssue("ISSUED", null, 1L, null);

		CheckOutcome outcome = new CouponIssueStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all(TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	void 빈_message_id는_계약_위반으로_검출한다() {
		insertIssue("ISSUED", null, 1L, "");

		CheckOutcome outcome = new CouponIssueStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all(TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getDiffDetail().get("sample").toString())
				.contains("INVALID_MESSAGE_ID_FORMAT");
	}

	@Test
	void 공백_message_id는_계약_위반으로_검출한다() {
		insertIssue("ISSUED", null, 1L, "   ");

		CheckOutcome outcome = new CouponIssueStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all(TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getDiffDetail().get("sample").toString())
				.contains("INVALID_MESSAGE_ID_FORMAT");
	}

	@Test
	void 길이만_36자인_비_UUID_request_id는_구조_위반으로_검출한다() {
		long issueId = insertIssue("ISSUED", null, 1L);
		jdbcTemplate.update("UPDATE coupon_issue SET request_id = ? WHERE issue_id = ?",
				"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx", issueId);

		CheckOutcome outcome = new CouponIssueStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all(TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
		assertThat(outcome.getDiffDetail().get("sample").toString())
				.contains("INVALID_REQUEST_ID");
	}

	@Test
	void canceled_at이_있는_CANCELED_상태는_구조_검증을_통과한다() {
		insertIssueWithCanceledAt("CANCELED", LocalDateTime.of(2026, 8, 18, 11, 0), 1L);

		CheckOutcome outcome = new CouponIssueStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all(TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isTrue();
		assertThat(outcome.getViolationCount()).isZero();
	}

	@Test
	void canceled_at이_없는_CANCELED_상태는_구조_위반으로_검출한다() {
		insertIssueWithCanceledAt("CANCELED", null, 1L);

		CheckOutcome outcome = new CouponIssueStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all(TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getDiffDetail().get("sample").toString())
				.contains("MISSING_CANCELED_AT");
	}

	@Test
	void 현재_상태와_최신_이력_상태가_일치하면_통과한다() {
		long issueId = insertIssue("USED", LocalDateTime.of(2026, 8, 18, 11, 0), 1L);
		insertHistory(issueId, null, "ISSUED", LocalDateTime.of(2026, 8, 18, 10, 0));
		insertHistory(issueId, "ISSUED", "USED", LocalDateTime.of(2026, 8, 18, 11, 0));

		CheckOutcome outcome = new CouponIssueHistoryStateConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all(TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	void 현재_상태와_최신_이력_상태가_다르면_위반으로_검출한다() {
		long issueId = insertIssue("USED", LocalDateTime.of(2026, 8, 18, 11, 0), 1L);
		insertHistory(issueId, null, "ISSUED", LocalDateTime.of(2026, 8, 18, 10, 0));

		CheckOutcome outcome = new CouponIssueHistoryStateConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all(TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
		assertThat(outcome.getDiffDetail().get("sample").toString())
				.contains("LATEST_STATUS_MISMATCH");
		assertThat(firstSample(outcome))
				.containsEntry("candidate_recovery_action", null)
				.containsEntry("manual_review_required", true);
	}

	@Test
	void 상태_이력_ALL_검증은_현재_페이지의_이벤트만_검사한다() {
		long targetIssueId = insertIssue("ISSUED", null, 1L);
		insertHistory(targetIssueId, null, "ISSUED", LocalDateTime.of(2026, 8, 18, 10, 0));
		long otherIssueId = insertIssue("USED", LocalDateTime.of(2026, 8, 18, 11, 0), 2L);
		insertHistory(otherIssueId, null, "ISSUED", LocalDateTime.of(2026, 8, 18, 10, 0));

		CheckOutcome outcome = new CouponIssueHistoryStateConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all(List.of(1L), TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	void 상태_이력이_없는_발급_건은_감사_이력_누락으로_검출한다() {
		insertIssue("ISSUED", null, 1L);

		CheckOutcome outcome = new CouponIssueHistoryStateConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all(TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
		assertThat(outcome.getDiffDetail().get("sample").toString())
				.contains("NO_HISTORY");
		assertThat(firstSample(outcome))
				.containsEntry("candidate_recovery_action", "RESTORE_INITIAL_ISSUE_HISTORY")
				.containsEntry("manual_review_required", false);
	}

	@Test
	void 상태_이력이_없는_USED는_초기_발급_이력_복구_후보가_아니다() {
		insertIssue("USED", LocalDateTime.of(2026, 8, 18, 11, 0), 1L);

		CheckOutcome outcome = new CouponIssueHistoryStateConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all(TEST_CHECKED_AT));

		assertThat(firstSample(outcome))
				.containsEntry("violation_type", "NO_HISTORY")
				.containsEntry("candidate_recovery_action", null)
				.containsEntry("manual_review_required", true);
	}

	@Test
	void 상태_이력이_없는_EXPIRED는_초기_발급_이력_복구_후보가_아니다() {
		insertIssue("EXPIRED", null, 1L);

		CheckOutcome outcome = new CouponIssueHistoryStateConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all(TEST_CHECKED_AT));

		assertThat(firstSample(outcome))
				.containsEntry("violation_type", "NO_HISTORY")
				.containsEntry("candidate_recovery_action", null)
				.containsEntry("manual_review_required", true);
	}

	@Test
	void 상태_이력이_없는_CANCELED는_초기_발급_이력_복구_후보가_아니다() {
		insertIssue("CANCELED", null, 1L);

		CheckOutcome outcome = new CouponIssueHistoryStateConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all(TEST_CHECKED_AT));

		assertThat(firstSample(outcome))
				.containsEntry("violation_type", "NO_HISTORY")
				.containsEntry("candidate_recovery_action", null)
				.containsEntry("manual_review_required", true);
	}

	@Test
	void 현재_상태가_NULL이고_최신_이력이_있으면_상태_불일치로_검출한다() {
		long issueId = insertIssue(null, null, 1L);
		insertHistory(issueId, null, "ISSUED", LocalDateTime.of(2026, 8, 18, 10, 0));

		CheckOutcome outcome = new CouponIssueHistoryStateConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all(TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
	}

	@Test
	void 최신_이력_상태가_NULL이면_현재_상태와의_불일치로_검출한다() {
		long issueId = insertIssue("ISSUED", null, 1L);
		insertHistory(issueId, null, null, LocalDateTime.of(2026, 8, 18, 10, 0));

		CheckOutcome outcome = new CouponIssueHistoryStateConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all(TEST_CHECKED_AT));

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
		insertHistory(issueId, "EXPIRED", "USED", LocalDateTime.of(2026, 8, 18, 11, 0));

		CheckOutcome outcome = new CouponHistoryStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all(TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
		assertThat(outcome.getDiffDetail().get("sample").toString())
				.contains("INVALID_STATUS_TRANSITION");
		assertThat(firstSample(outcome))
				.containsEntry("candidate_recovery_action", null)
				.containsEntry("manual_review_required", true);
	}

	@Test
	void 이력_구조_ALL_검증은_현재_페이지의_이벤트만_검사한다() {
		long targetIssueId = insertIssue("ISSUED", null, 1L);
		insertHistory(targetIssueId, null, "ISSUED", LocalDateTime.of(2026, 8, 18, 10, 0));
		long otherIssueId = insertIssue("USED", LocalDateTime.of(2026, 8, 18, 11, 0), 2L);
		insertHistory(otherIssueId, "EXPIRED", "USED", LocalDateTime.of(2026, 8, 18, 11, 0));

		CheckOutcome outcome = new CouponHistoryStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all(List.of(1L), TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	void 이력_구조_EVENT_검증은_다른_이벤트의_구조_위반을_제외한다() {
		long targetIssueId = insertIssue("ISSUED", null, 1L);
		insertHistory(targetIssueId, null, "ISSUED", LocalDateTime.of(2026, 8, 18, 10, 0));
		long otherIssueId = insertIssue("USED", LocalDateTime.of(2026, 8, 18, 11, 0), 2L);
		insertHistory(otherIssueId, "EXPIRED", "USED", LocalDateTime.of(2026, 8, 18, 11, 0));

		CheckOutcome outcome = new CouponHistoryStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.ofEvent(1L));

		assertThat(outcome.isPass()).isTrue();
		assertThat(outcome.getViolationCount()).isZero();
	}

	@Test
	void 사용_취소에_따른_USED에서_ISSUED로의_복원을_허용한다() {
		long issueId = insertIssue("ISSUED", null, 1L);
		insertHistory(issueId, "USED", "ISSUED", LocalDateTime.of(2026, 8, 18, 11, 0));

		CheckOutcome outcome = new CouponHistoryStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all(TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isTrue();
		assertThat(outcome.getViolationCount()).isZero();
	}

	@Test
	void 재고_초과발급_회수에_따른_ISSUED에서_CANCELED로의_전이를_허용한다() {
		long issueId = insertIssueWithCanceledAt("CANCELED", LocalDateTime.of(2026, 8, 18, 11, 0), 1L);
		insertHistory(issueId, "ISSUED", "CANCELED", LocalDateTime.of(2026, 8, 18, 11, 0));

		CheckOutcome outcome = new CouponHistoryStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all(TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isTrue();
		assertThat(outcome.getViolationCount()).isZero();
	}

	@Test
	void 이력_구조_AS_OF_RANGE는_recorded_at이_from과_같은_행을_포함한다() {
		long issueId = insertIssue("USED", LocalDateTime.of(2026, 8, 18, 11, 0), 1L);
		LocalDateTime from = LocalDateTime.of(2026, 8, 18, 11, 0);
		insertHistory(issueId, "EXPIRED", "USED",
				LocalDateTime.of(2026, 8, 18, 10, 0), from);

		CheckOutcome outcome = new CouponHistoryStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.ofAsOfRange(from, TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
	}

	@Test
	void 이력_구조_AS_OF_RANGE는_recorded_at이_from보다_이전인_행을_제외한다() {
		long issueId = insertIssue("USED", LocalDateTime.of(2026, 8, 18, 11, 0), 1L);
		insertHistory(issueId, "EXPIRED", "USED",
				LocalDateTime.of(2026, 8, 18, 10, 0),
				LocalDateTime.of(2026, 8, 18, 10, 59, 59));

		CheckOutcome outcome = new CouponHistoryStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.ofAsOfRange(
						LocalDateTime.of(2026, 8, 18, 11, 0), TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	void 이력_구조_AS_OF_RANGE는_recorded_at이_to와_같은_행을_제외한다() {
		long issueId = insertIssue("USED", LocalDateTime.of(2026, 8, 18, 11, 0), 1L);
		insertHistory(issueId, "EXPIRED", "USED",
				LocalDateTime.of(2026, 8, 18, 10, 0), TEST_CHECKED_AT);

		CheckOutcome outcome = new CouponHistoryStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.ofAsOfRange(
						LocalDateTime.of(2026, 8, 18, 11, 0), TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	void 이력_구조_AS_OF_RANGE는_과거에_발생했지만_구간_내_기록된_행을_검출한다() {
		long issueId = insertIssue("USED", LocalDateTime.of(2026, 8, 18, 11, 0), 1L);
		insertHistory(issueId, "EXPIRED", "USED",
				LocalDateTime.of(2026, 8, 17, 10, 0),
				LocalDateTime.of(2026, 8, 18, 11, 30));

		CheckOutcome outcome = new CouponHistoryStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.ofAsOfRange(
						LocalDateTime.of(2026, 8, 18, 11, 0), TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
	}

	@Test
	void 이력_구조_AS_OF_RANGE는_recorded_at이_NULL인_위반을_누락하지_않는다() {
		long issueId = insertIssue("ISSUED", null, 1L);
		jdbcTemplate.update("""
				INSERT INTO coupon_history (
				  issue_id, from_status, to_status, occurred_at, recorded_at
				) VALUES (?, ?, ?, ?, NULL)
				""", issueId, null, "ISSUED",
				Timestamp.valueOf(LocalDateTime.of(2026, 8, 18, 10, 0)));

		CheckOutcome outcome = new CouponHistoryStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.ofAsOfRange(
						LocalDateTime.of(2026, 8, 18, 11, 0), TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getDiffDetail().get("sample").toString())
				.contains("MISSING_TIMESTAMP");
	}

	@Test
	void 만료_후_사용된_행을_만료_시차_위반으로_검출한다() {
		insertIssue("USED", LocalDateTime.of(2026, 8, 18, 11, 0), 1L);
		Scope scope = Scope.ofAsOfRange(
				LocalDateTime.of(2026, 8, 18, 10, 30),
				LocalDateTime.of(2026, 8, 18, 12, 0));

		CheckOutcome outcome = expirationLagCheck().check(scope);

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
		assertThat(outcome.getDiffDetail().get("sample").toString())
				.contains("USED_AFTER_EXPIRATION");
		assertThat(firstSample(outcome))
				.containsEntry("candidate_recovery_action", null)
				.containsEntry("manual_review_required", true);
	}

	@Test
	void 검증_기준_시각_이후의_사용은_현재_스냅샷에서_제외한다() {
		insertIssue("USED", LocalDateTime.of(2026, 8, 18, 12, 30), 1L);
		Scope scope = Scope.ofAsOfRange(
				LocalDateTime.of(2026, 8, 18, 10, 30),
				LocalDateTime.of(2026, 8, 18, 12, 0));

		CheckOutcome outcome = expirationLagCheck().check(scope);

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

		CheckOutcome outcome = expirationLagCheck().check(scope);

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	void 허용_경계를_넘긴_ISSUED_행을_만료_지연으로_검출한다() {
		insertIssue("ISSUED", null, 1L);
		Scope scope = Scope.ofAsOfRange(
				LocalDateTime.of(2026, 8, 18, 10, 30),
				LocalDateTime.of(2026, 8, 18, 12, 0));

		CheckOutcome outcome = expirationLagCheck().check(scope);

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
		assertThat(outcome.getDiffDetail().get("sample").toString())
				.contains("EXPIRATION_BATCH_DELAY");
		assertThat(firstSample(outcome))
				.containsEntry("candidate_recovery_action", "EXPIRE_DELAYED_ISSUE")
				.containsEntry("manual_review_required", false);
	}

	@Test
	void EVENT_범위에서는_대상_이벤트의_만료_위반만_검출한다() {
		insertIssue("ISSUED", null, 1L);
		insertIssue("ISSUED", null, 2L);

		CheckOutcome outcome = expirationLagCheck().check(Scope.ofEvent(1L));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
		assertThat(outcome.getDiffDetail()).containsEntry("eventId", 1L);
	}

	@Test
	void ALL_범위에서는_페이지에_포함된_이벤트의_만료_위반만_검출한다() {
		insertIssue("ISSUED", null, 1L);
		insertIssue("ISSUED", null, 2L);

		CheckOutcome outcome = expirationLagCheck()
				.check(Scope.all(List.of(1L), TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
		assertThat(outcome.getDiffDetail()).containsEntry("eventIds", List.of(1L));
	}

	@Test
	void ALL_범위에서는_to_이후에_사용된_행을_스냅샷에서_제외한다() {
		insertIssue("USED", LocalDateTime.of(2026, 8, 18, 12, 30), 1L);

		CheckOutcome outcome = expirationLagCheck()
				.check(Scope.all(List.of(1L), TEST_CHECKED_AT));

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	void valid_to가_from_이전이면_검증_구간에서_제외한다() {
		insertIssue("ISSUED", null, 1L);
		Scope scope = Scope.ofAsOfRange(
				LocalDateTime.of(2026, 8, 18, 10, 31),
				LocalDateTime.of(2026, 8, 18, 12, 0));

		CheckOutcome outcome = expirationLagCheck().check(scope);

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	void valid_to가_from과_같으면_검증_구간에_포함한다() {
		insertIssue("ISSUED", null, 1L);
		Scope scope = Scope.ofAsOfRange(
				LocalDateTime.of(2026, 8, 18, 10, 30),
				LocalDateTime.of(2026, 8, 18, 12, 0));

		CheckOutcome outcome = expirationLagCheck().check(scope);

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
	}

	@Test
	void valid_to가_to와_같으면_검증_구간에서_제외한다() {
		insertIssue("ISSUED", null, 1L);
		Scope scope = Scope.ofAsOfRange(
				LocalDateTime.of(2026, 8, 18, 10, 0),
				LocalDateTime.of(2026, 8, 18, 10, 30));

		CheckOutcome outcome = expirationLagCheck().check(scope);

		assertThat(outcome.isPass()).isTrue();
	}

	private CouponExpirationLagConsistencyCheck expirationLagCheck() {
		return new CouponExpirationLagConsistencyCheck(
				namedJdbcTemplate, TEST_ALLOWED_EXPIRATION_DELAY_MS, TEST_CLOCK);
	}

	@Test
	void 복구_정책_메타정보는_diffDetail_JSON에_null과_boolean을_유지한다() {
		insertIssue(null, null, 1L);
		CheckOutcome outcome = new CouponIssueStructuralConsistencyCheck(namedJdbcTemplate)
				.check(Scope.all(TEST_CHECKED_AT));

		DiffDetailConverter converter = new DiffDetailConverter();
		String json = converter.convertToDatabaseColumn(outcome.getDiffDetail());

		assertThat(json)
				.contains("\"candidate_recovery_action\":null")
				.contains("\"manual_review_required\":true");
		assertThat(converter.convertToEntityAttribute(json)).containsKey("sample");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> firstSample(CheckOutcome outcome) {
		return ((List<Map<String, Object>>) outcome.getDiffDetail().get("sample")).getFirst();
	}

	private long insertIssue(String status, LocalDateTime usedAt, long eventId) {
		return insertIssue(status, usedAt, eventId,
				IssueRecord.messageId(eventId, "1755000000000-0"));
	}

	private long insertIssueWithCanceledAt(String status, LocalDateTime canceledAt, long eventId) {
		long issueId = insertIssue(status, null, eventId);
		jdbcTemplate.update("UPDATE coupon_issue SET canceled_at = ? WHERE issue_id = ?",
				canceledAt == null ? null : Timestamp.valueOf(canceledAt), issueId);
		return issueId;
	}

	private long insertIssue(String status, LocalDateTime usedAt, long eventId, String messageId) {
		LocalDateTime issuedAt = LocalDateTime.of(2026, 8, 18, 9, 0);
		LocalDateTime validTo = LocalDateTime.of(2026, 8, 18, 10, 30);
		jdbcTemplate.update("""
				INSERT INTO coupon_issue (
				  event_id, user_id, issue_sequence, request_id, status,
				  issued_at, valid_from, valid_to, used_at, created_at, message_id
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
		insertHistory(issueId, fromStatus, toStatus, occurredAt, occurredAt);
	}

	private void insertHistory(long issueId, String fromStatus, String toStatus,
			LocalDateTime occurredAt, LocalDateTime recordedAt) {
		jdbcTemplate.update("""
				INSERT INTO coupon_history (
				  issue_id, from_status, to_status, occurred_at, recorded_at
				) VALUES (?, ?, ?, ?, ?)
				""",
				issueId, fromStatus, toStatus,
				Timestamp.valueOf(occurredAt), Timestamp.valueOf(recordedAt));
	}
}
