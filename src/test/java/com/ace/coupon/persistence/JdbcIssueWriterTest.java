package com.ace.coupon.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ace.coupon.redis.CouponIssueRedisProperties;

// 실제 로컬 MySQL 에 대고 저장 SQL 을 검증
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JdbcIssueWriterTest {

	private static final int VALID_HOURS = 168;
	private static final Instant DECIDED_AT =
			Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private JdbcIssueWriter writer;
	private CampaignMetadata metadata;
	private long userId;

	@BeforeEach
	void setUp() {
		writer = new JdbcIssueWriter(jdbcTemplate, new CouponIssueRedisProperties(null, null));

		LocalDateTime now = LocalDateTime.now();
		jdbcTemplate.update("""
				INSERT INTO coupon (coupon_name, type, value, valid_hours, created_at)
				VALUES ('테스트 쿠폰', 'FIXED', 1000, ?, ?)
				""", VALID_HOURS, now);
		long couponId = lastInsertId();

		jdbcTemplate.update("""
				INSERT INTO coupon_event
				    (coupon_id, round, open_at, close_at, total_stock, remaining_stock,
				     issued_quantity, per_user_limit, status, created_at, updated_at)
				VALUES (?, 9999, ?, ?, 10000, 10000, 0, 1, 'OPEN', ?, ?)
				""", couponId, now.minusMinutes(1), now.plusHours(1), now, now);
		long eventId = lastInsertId();

		userId = jdbcTemplate.queryForObject("SELECT MIN(user_id) FROM user", Long.class);
		metadata = new CampaignMetadata(eventId, VALID_HOURS, now.minusMinutes(1), now.plusHours(1));
	}

	private long lastInsertId() {
		return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private IssueRecord record(UUID requestId, long sequence, String messageId) {
		return new IssueRecord(
				requestId, metadata.eventId(), userId, 0L, userId - 1, sequence, DECIDED_AT, messageId);
	}

	private LocalDateTime dateTime(Object value) {
		return value instanceof java.sql.Timestamp timestamp
				? timestamp.toLocalDateTime()
				: (LocalDateTime) value;
	}

	private Map<String, Object> issueRow(long issueId) {
		return jdbcTemplate.queryForMap("SELECT * FROM coupon_issue WHERE issue_id = ?", issueId);
	}

	private int issueCount() {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM coupon_issue WHERE event_id = ?", Integer.class, metadata.eventId());
	}

	private int historyCount(long issueId) {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM coupon_history WHERE issue_id = ?", Integer.class, issueId);
	}

	@Test
	@DisplayName("발급과 이력을 함께 저장한다")
	void writesIssueAndHistory() {
		UUID requestId = UUID.randomUUID();

		long issueId = writer.write(record(requestId, 1L, null), metadata);

		assertThat(issueId).isPositive();
		assertThat(issueCount()).isEqualTo(1);
		assertThat(historyCount(issueId)).isEqualTo(1);

		Map<String, Object> issue = issueRow(issueId);
		assertThat(issue.get("request_id")).isEqualTo(requestId.toString());
		assertThat(issue.get("status")).isEqualTo("ISSUED");
		assertThat(issue.get("issue_sequence")).isEqualTo(1);
		assertThat(issue.get("message_id")).isNull();
	}

	@Test
	@DisplayName("같은 요청을 다시 저장해도 행이 늘지 않고 같은 식별자가 돌아온다")
	void isIdempotent() {
		IssueRecord record = record(UUID.randomUUID(), 1L, null);

		long first = writer.write(record, metadata);
		long second = writer.write(record, metadata);

		assertThat(second).isEqualTo(first);
		assertThat(issueCount()).isEqualTo(1);
		assertThat(historyCount(first)).isEqualTo(1);
	}

	@Test
	@DisplayName("발급 시각은 판정 시각을 쓴다")
	void usesDecidedAtAsIssuedAt() {
		long issueId = writer.write(record(UUID.randomUUID(), 1L, null), metadata);

		Map<String, Object> issue = issueRow(issueId);
		LocalDateTime issuedAt = dateTime(issue.get("issued_at"));
		LocalDateTime expected = LocalDateTime.ofInstant(DECIDED_AT, java.time.ZoneId.of("Asia/Seoul"));

		assertThat(issuedAt).isEqualTo(expected);
		assertThat(issue.get("valid_from")).isEqualTo(issue.get("issued_at"));
	}

	@Test
	@DisplayName("유효기간은 회차 쿠폰의 valid_hours로 계산한다")
	void calculatesValidTo() {
		long issueId = writer.write(record(UUID.randomUUID(), 1L, null), metadata);

		Map<String, Object> issue = issueRow(issueId);
		LocalDateTime validFrom = dateTime(issue.get("valid_from"));
		LocalDateTime validTo = dateTime(issue.get("valid_to"));

		assertThat(Duration.between(validFrom, validTo)).isEqualTo(Duration.ofHours(VALID_HOURS));
	}

	@Test
	@DisplayName("이력은 발생 시각과 기록 시각을 나눠 담는다")
	void separatesOccurredAndRecorded() {
		long issueId = writer.write(record(UUID.randomUUID(), 1L, null), metadata);

		Map<String, Object> history = jdbcTemplate.queryForMap(
				"SELECT * FROM coupon_history WHERE issue_id = ?", issueId);
		LocalDateTime occurredAt = dateTime(history.get("occurred_at"));
		LocalDateTime recordedAt = dateTime(history.get("recorded_at"));

		assertThat(occurredAt)
				.isEqualTo(LocalDateTime.ofInstant(DECIDED_AT, java.time.ZoneId.of("Asia/Seoul")));
		assertThat(recordedAt).isAfter(occurredAt);
		assertThat(history.get("from_status")).isNull();
		assertThat(history.get("to_status")).isEqualTo("ISSUED");
	}

	@Test
	@DisplayName("Stream 엔트리 식별자를 message_id로 저장한다")
	void storesMessageId() {
		long issueId = writer.write(record(UUID.randomUUID(), 1L, "1755000000000-0"), metadata);

		assertThat(issueRow(issueId).get("message_id")).isEqualTo("1755000000000-0");
	}

	@Test
	@DisplayName("같은 순번을 다른 사용자로 저장하면 드러낸다")
	void rejectsDuplicateSequence() {
		long first = writer.write(record(UUID.randomUUID(), 1L, null), metadata);

		long otherUserId = jdbcTemplate.queryForObject(
				"SELECT MIN(user_id) FROM user WHERE user_id > ?", Long.class, userId);
		IssueRecord conflict = new IssueRecord(
				UUID.randomUUID(), metadata.eventId(), otherUserId, 0L, otherUserId - 1,
				1L, DECIDED_AT, null);

		assertThatThrownBy(() -> writer.write(conflict, metadata))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("UNIQUE 충돌");

		// 남의 발급에 이력이 붙지 않아야 함
		assertThat(historyCount(first)).isEqualTo(1);
	}

	@Test
	@DisplayName("같은 사용자가 다른 요청으로 오면 드러낸다")
	void rejectsSameUserWithDifferentRequest() {
		writer.write(record(UUID.randomUUID(), 1L, null), metadata);

		assertThatThrownBy(() -> writer.write(record(UUID.randomUUID(), 2L, null), metadata))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("UNIQUE 충돌");

		assertThat(issueCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("회차가 어긋난 입력은 저장하지 않는다")
	void rejectsMismatchedCampaign() {
		IssueRecord other = new IssueRecord(
				UUID.randomUUID(), metadata.eventId() + 1, userId, 0L, userId - 1, 1L, DECIDED_AT, null);

		assertThatThrownBy(() -> writer.write(other, metadata))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("일치하지 않습니다");
	}
}
