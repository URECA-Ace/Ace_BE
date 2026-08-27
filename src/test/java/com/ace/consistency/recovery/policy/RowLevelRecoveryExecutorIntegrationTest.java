package com.ace.consistency.recovery.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResult;
import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.recovery.ConsistencyRecoveryDispatcher;
import com.ace.consistency.recovery.RecoveryOutcome;
import com.ace.consistency.recovery.enums.RecoveryResultStatus;
import com.ace.consistency.recovery.enums.RecoveryAction;
import com.ace.consistency.recovery.repository.RecoveryResultRepository;
import com.ace.consistency.repository.VerificationResultRepository;

@SpringBootTest(properties = {
		"spring.autoconfigure.exclude=org.springframework.boot.batch.autoconfigure.BatchAutoConfiguration",
		"consistency.expiration.allowed-delay-ms=1800000"
})
@Testcontainers
class RowLevelRecoveryExecutorIntegrationTest {

	@Container
	@ServiceConnection
	static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"));

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
	}

	@Autowired JdbcTemplate jdbcTemplate;
	@Autowired CouponIssueHistoryStateRecoveryExecutor historyExecutor;
	@Autowired CouponExpirationLagRecoveryExecutor expirationExecutor;
	@Autowired ConsistencyRecoveryDispatcher dispatcher;
	@Autowired VerificationResultRepository verificationResultRepository;
	@Autowired RecoveryResultRepository recoveryResultRepository;

	private long eventId;
	private long issueId;
	private final LocalDateTime issuedAt = LocalDateTime.of(2026, 8, 25, 8, 0);

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM recovery_result");
		jdbcTemplate.update("DELETE FROM verification_result");
		jdbcTemplate.update("DELETE FROM coupon_history");
		jdbcTemplate.update("DELETE FROM coupon_issue");
		jdbcTemplate.update("DELETE FROM coupon_event");
		jdbcTemplate.update("DELETE FROM coupon");
		jdbcTemplate.update("DELETE FROM user");
		jdbcTemplate.update("INSERT INTO user(email, name, created_at) VALUES (?, ?, ?)",
				"recovery@test.com", "recovery", issuedAt.minusDays(1));
		long userId = jdbcTemplate.queryForObject("SELECT user_id FROM user", Long.class);
		jdbcTemplate.update("INSERT INTO coupon(coupon_name, type, value, valid_hours, created_at) VALUES (?, ?, ?, ?, ?)",
				"recovery coupon", "FIXED", 1000, 1, issuedAt.minusDays(1));
		long couponId = jdbcTemplate.queryForObject("SELECT coupon_id FROM coupon", Long.class);
		jdbcTemplate.update("""
				INSERT INTO coupon_event(coupon_id, round, open_at, close_at, total_stock,
				 remaining_stock, issued_quantity, per_user_limit, status, created_at, updated_at)
				VALUES (?, 1, ?, ?, 10, 9, 1, 1, 'CLOSED', ?, ?)
				""", couponId, issuedAt.minusHours(1), issuedAt.plusHours(2), issuedAt.minusDays(1), issuedAt);
		eventId = jdbcTemplate.queryForObject("SELECT event_id FROM coupon_event", Long.class);
		issueId = insertIssue(userId, 1, "ISSUED", issuedAt.plusHours(1));
	}

	@Test
	void NO_HISTORY_ISSUED는_초기이력을_한번만_복원한다() {
		VerificationResultEntity target = target("CouponIssueHistoryStateConsistencyCheck", "NO_HISTORY", issueId);

		RecoveryOutcome first = historyExecutor.recoverIssue(target, issueId);
		RecoveryOutcome second = historyExecutor.recoverIssue(target, issueId);

		assertThat(first.getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		assertThat(first.getDetail()).containsKeys("before", "after", "issueId", "violationType");
		assertThat(second.getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		assertThat(second.getDetail()).containsEntry("catchUp", true);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_history WHERE issue_id=?", Integer.class, issueId))
				.isEqualTo(1);
	}

	@Test
	void 사용_또는_취소_흔적이_있으면_초기이력을_복원하지_않는다() {
		jdbcTemplate.update("UPDATE coupon_issue SET canceled_at=? WHERE issue_id=?", LocalDateTime.now(), issueId);

		RecoveryOutcome outcome = historyExecutor.recoverIssue(
				target("CouponIssueHistoryStateConsistencyCheck", "NO_HISTORY", issueId), issueId);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_history WHERE issue_id=?", Integer.class, issueId))
				.isZero();
	}

	@Test
	void 한_issue가_실패해도_다른_issue의_REQUIRES_NEW_복구는_commit된다() {
		jdbcTemplate.update("INSERT INTO user(email, name, created_at) VALUES (?, ?, ?)",
				"unsafe-recovery@test.com", "unsafe-recovery", issuedAt.minusDays(1));
		long userId = jdbcTemplate.queryForObject(
				"SELECT user_id FROM user WHERE email=?", Long.class, "unsafe-recovery@test.com");
		long unsafeIssueId = insertIssue(userId, 2, "ISSUED", issuedAt.plusHours(1));
		jdbcTemplate.update("UPDATE coupon_issue SET used_at=? WHERE issue_id=?", LocalDateTime.now(), unsafeIssueId);
		VerificationResultEntity target = target("CouponIssueHistoryStateConsistencyCheck", "NO_HISTORY", issueId);

		RecoveryOutcome success = historyExecutor.recoverIssue(target, issueId);
		RecoveryOutcome failure = historyExecutor.recoverIssue(target, unsafeIssueId);

		assertThat(success.getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		assertThat(failure.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_history WHERE issue_id=?", Integer.class, issueId))
				.isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_history WHERE issue_id=?", Integer.class, unsafeIssueId))
				.isZero();
	}

	@Test
	void Dispatcher는_복구결과를_저장하고_EVENT_재검증_통과시_RECOVERED로_갱신한다() {
		VerificationResultEntity target = verificationResultRepository.save(
				target("CouponIssueHistoryStateConsistencyCheck", "NO_HISTORY", issueId));

		var results = dispatcher.recover(target.getId(), RecoveryAction.RESTORE_INITIAL_ISSUE_HISTORY);

		assertThat(results).singleElement()
				.satisfies(result -> assertThat(result.getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS));
		assertThat(recoveryResultRepository.findAll()).hasSize(1);
		assertThat(verificationResultRepository.findById(target.getId()).orElseThrow().getRecoveryStatus())
				.isEqualTo(VerificationResultEntity.RecoveryStatus.RECOVERED);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_history WHERE issue_id=?", Integer.class, issueId))
				.isEqualTo(1);
	}

	@Test
	void Dispatcher_다중_issue는_성공_target만_commit하고_각_RecoveryResult를_남긴다() {
		jdbcTemplate.update("INSERT INTO user(email, name, created_at) VALUES (?, ?, ?)",
				"partial-recovery@test.com", "partial-recovery", issuedAt.minusDays(1));
		long otherUserId = jdbcTemplate.queryForObject(
				"SELECT user_id FROM user WHERE email=?", Long.class, "partial-recovery@test.com");
		long unsafeIssueId = insertIssue(otherUserId, 2, "ISSUED", issuedAt.plusHours(1));
		jdbcTemplate.update("UPDATE coupon_issue SET canceled_at=? WHERE issue_id=?", LocalDateTime.now(), unsafeIssueId);
		List<Map<String, Object>> sample = List.of(
				Map.of("issue_id", issueId, "event_id", eventId, "violation_type", "NO_HISTORY"),
				Map.of("issue_id", unsafeIssueId, "event_id", eventId, "violation_type", "NO_HISTORY"));
		VerificationResultEntity target = verificationResultRepository.save(
				VerificationResultEntity.from(VerificationResult.fail(
						"CouponIssueHistoryStateConsistencyCheck", TriggerType.ON_DEMAND, Scope.ofEvent(eventId),
						2, Map.of("sample", sample), LocalDateTime.now(), 1L)));

		var results = dispatcher.recover(target.getId(), RecoveryAction.RESTORE_INITIAL_ISSUE_HISTORY);

		assertThat(results).extracting(result -> result.getStatus())
				.containsExactly(RecoveryResultStatus.SUCCESS, RecoveryResultStatus.FAIL);
		assertThat(recoveryResultRepository.findAll()).hasSize(2);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_history WHERE issue_id=?", Integer.class, issueId))
				.isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_history WHERE issue_id=?", Integer.class, unsafeIssueId))
				.isZero();
		assertThat(verificationResultRepository.findById(target.getId()).orElseThrow().getRecoveryStatus())
				.isEqualTo(VerificationResultEntity.RecoveryStatus.RECOVERY_FAILED);
	}

	@Test
	void 만료지연_issue는_EXPIRED와_History를_원자적으로_저장하고_재요청은_noop이다() {
		jdbcTemplate.update("UPDATE coupon_issue SET valid_to=?, created_at=? WHERE issue_id=?",
				LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(3), issueId);
		VerificationResultEntity target = target("CouponExpirationLagConsistencyCheck", "EXPIRATION_BATCH_DELAY", issueId);

		RecoveryOutcome first = expirationExecutor.recoverIssue(target, issueId);
		RecoveryOutcome second = expirationExecutor.recoverIssue(target, issueId);

		assertThat(first.getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		assertThat(first.getDetail()).containsKeys("before", "after", "issueId", "violationType");
		assertThat(second.getDetail()).containsEntry("catchUp", true);
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM coupon_issue WHERE issue_id=?", String.class, issueId))
				.isEqualTo("EXPIRED");
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_history WHERE issue_id=?", Integer.class, issueId))
				.isEqualTo(1);
	}

	@Test
	void 만료_History_INSERT가_실패하면_Issue_UPDATE도_rollback된다() {
		jdbcTemplate.update("UPDATE coupon_issue SET valid_to=?, created_at=? WHERE issue_id=?",
				LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(3), issueId);
		VerificationResultEntity target = target("CouponExpirationLagConsistencyCheck", "EXPIRATION_BATCH_DELAY", issueId);
		jdbcTemplate.execute("ALTER TABLE coupon_history MODIFY actor VARCHAR(1)");
		try {
			RecoveryOutcome outcome = expirationExecutor.recoverIssue(target, issueId);
			assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		} finally {
			jdbcTemplate.execute("ALTER TABLE coupon_history MODIFY actor VARCHAR(20)");
		}

		assertThat(jdbcTemplate.queryForObject("SELECT status FROM coupon_issue WHERE issue_id=?", String.class, issueId))
				.isEqualTo("ISSUED");
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_history WHERE issue_id=?", Integer.class, issueId))
				.isZero();
	}

	private long insertIssue(long userId, int sequence, String status, LocalDateTime validTo) {
		jdbcTemplate.update("""
				INSERT INTO coupon_issue(event_id, user_id, issue_sequence, request_id, status,
				 issued_at, valid_from, valid_to, created_at, message_id)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
				""", eventId, userId, sequence, UUID.randomUUID().toString(), status,
				issuedAt, issuedAt, validTo, issuedAt);
		return jdbcTemplate.queryForObject("SELECT issue_id FROM coupon_issue WHERE event_id=? AND issue_sequence=?",
				Long.class, eventId, sequence);
	}

	private VerificationResultEntity target(String checkName, String violationType, long targetIssueId) {
		Map<String, Object> sample = Map.of(
				"issue_id", targetIssueId,
				"event_id", eventId,
				"violation_type", violationType);
		return VerificationResultEntity.from(VerificationResult.fail(
				checkName, TriggerType.ON_DEMAND, Scope.ofEvent(eventId), 1,
				Map.of("sample", List.of(sample)), LocalDateTime.now(), 1L));
	}
}
