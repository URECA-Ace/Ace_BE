package com.ace.consistency.rowlevel.service;

import com.ace.consistency.rowlevel.domain.ValidationStatus;
import com.ace.consistency.rowlevel.dto.CouponEventRow;
import com.ace.consistency.rowlevel.dto.CouponHistoryRow;
import com.ace.consistency.rowlevel.dto.CouponIssueRow;
import com.ace.consistency.rowlevel.dto.CouponRow;
import com.ace.consistency.rowlevel.dto.RowValidationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RowLevelValidationServiceTest {

	private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");
	private static final LocalDateTime SNAPSHOT_AT = LocalDateTime.of(2026, 8, 12, 12, 0);
	private RowLevelValidationService service;

	@BeforeEach
	void setUp() {
		Clock clock = Clock.fixed(Instant.parse("2026-08-12T03:00:00Z"), ZONE_ID);
		service = new RowLevelValidationService(clock);
	}

	@Test
	void 유효한_쿠폰_행은_통과한다() {
		CouponRow row = new CouponRow(
				1L, "프리덤 아워", "5gdata", 0L, 24,
				LocalDateTime.of(2026, 8, 12, 10, 0)
		);

		RowValidationResponse response = service.validateCoupon(row, SNAPSHOT_AT);

		assertThat(response.status()).isEqualTo(ValidationStatus.PASS);
		assertThat(response.failCount()).isZero();
	}

	@Test
	void 검증_기준시각을_생략하면_재현성을_위해_거부한다() {
		CouponRow row = new CouponRow(
				1L, "프리덤 아워", "5gdata", 0L, 24,
				LocalDateTime.of(2026, 8, 12, 10, 0)
		);

		assertThatThrownBy(() -> service.validateCoupon(row, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("snapshotAt");
	}

	@Test
	void 쿠폰의_값이_음수이면_실패한다() {
		CouponRow row = new CouponRow(
				1L, "프리덤 아워", "5gdata", -1L, 24,
				LocalDateTime.of(2026, 8, 12, 10, 0)
		);

		RowValidationResponse response = service.validateCoupon(row, SNAPSHOT_AT);

		assertThat(response.results()).anyMatch(result -> result.checkId().equals("coupon_numeric_range")
				&& result.status() == ValidationStatus.FAIL);
	}

	@Test
	void 유효한_쿠폰_이벤트_행은_통과한다() {
		CouponEventRow row = new CouponEventRow(
				1L, 1L, 1,
				LocalDateTime.of(2026, 8, 12, 10, 0),
				LocalDateTime.of(2026, 8, 12, 13, 0),
				10_000, 9_000, 1_000, 1, "OPEN",
				LocalDateTime.of(2026, 8, 12, 9, 0),
				LocalDateTime.of(2026, 8, 12, 11, 0)
		);

		RowValidationResponse response = service.validateEvent(row, SNAPSHOT_AT);

		assertThat(response.status()).isEqualTo(ValidationStatus.PASS);
		assertThat(response.failCount()).isZero();
	}

	@Test
	void 이벤트_재고_범위를_벗어나면_실패한다() {
		CouponEventRow row = new CouponEventRow(
				1L, 1L, 1,
				LocalDateTime.of(2026, 8, 12, 10, 0),
				LocalDateTime.of(2026, 8, 12, 13, 0),
				10_000, -1, 10_001, 1, "OPEN",
				LocalDateTime.of(2026, 8, 12, 9, 0),
				LocalDateTime.of(2026, 8, 12, 11, 0)
		);

		RowValidationResponse response = service.validateEvent(row, SNAPSHOT_AT);

		assertThat(response.status()).isEqualTo(ValidationStatus.FAIL);
		assertThat(response.results()).anyMatch(result -> result.checkId().equals("event_numeric_range")
				&& result.status() == ValidationStatus.FAIL);
	}

	@Test
	void 유효한_발급_행은_통과한다() {
		CouponIssueRow row = validIssuedRow("ISSUED", LocalDateTime.of(2026, 8, 13, 10, 0), null, null);

		RowValidationResponse response = service.validateIssue(row, SNAPSHOT_AT);

		assertThat(response.status()).isEqualTo(ValidationStatus.PASS);
		assertThat(response.failCount()).isZero();
	}

	@Test
	void 유효기간이_지났는데_발급_상태이면_만료_시차_검증에_실패한다() {
		CouponIssueRow row = validIssuedRow("ISSUED", LocalDateTime.of(2026, 8, 12, 11, 59), null, null);

		RowValidationResponse response = service.validateIssue(row, SNAPSHOT_AT);

		assertThat(response.results()).anyMatch(result -> result.checkId().equals("issue_expiration_timing")
				&& result.status() == ValidationStatus.FAIL);
	}

	@Test
	void 사용_상태인데_usedAt이_없으면_실패한다() {
		CouponIssueRow row = validIssuedRow("USED", LocalDateTime.of(2026, 8, 13, 10, 0), null, null);

		RowValidationResponse response = service.validateIssue(row, SNAPSHOT_AT);

		assertThat(response.results()).anyMatch(result -> result.checkId().equals("issue_status_fields")
				&& result.status() == ValidationStatus.FAIL);
	}

	@Test
	void 선택값인_messageId가_없어도_발급_행은_통과한다() {
		LocalDateTime issuedAt = LocalDateTime.of(2026, 8, 12, 10, 0);
		CouponIssueRow row = new CouponIssueRow(
				1L, 1L, 1L, 1,
				"11111111-1111-1111-1111-111111111111", null,
				"ISSUED", issuedAt, issuedAt,
				LocalDateTime.of(2026, 8, 13, 10, 0), null, null, issuedAt
		);

		assertThat(service.validateIssue(row, SNAPSHOT_AT).status()).isEqualTo(ValidationStatus.PASS);
	}

	@Test
	void 유효기간이_발급시각_이후에_시작해도_시간순서가_맞으면_통과한다() {
		LocalDateTime issuedAt = LocalDateTime.of(2026, 8, 12, 9, 59);
		LocalDateTime validFrom = LocalDateTime.of(2026, 8, 12, 10, 0);
		CouponIssueRow row = new CouponIssueRow(
				1L, 1L, 1L, 1,
				"11111111-1111-1111-1111-111111111111", null,
				"ISSUED", issuedAt, validFrom,
				LocalDateTime.of(2026, 8, 13, 10, 0), null, null, issuedAt
		);

		assertThat(service.validateIssue(row, SNAPSHOT_AT).status()).isEqualTo(ValidationStatus.PASS);
	}

	@Test
	void 발급_기록시각이_검증시각보다_미래이면_실패한다() {
		LocalDateTime issuedAt = LocalDateTime.of(2026, 8, 12, 10, 0);
		CouponIssueRow row = new CouponIssueRow(
				1L, 1L, 1L, 1,
				"11111111-1111-1111-1111-111111111111", null,
				"ISSUED", issuedAt, issuedAt,
				LocalDateTime.of(2026, 8, 13, 10, 0), null, null,
				SNAPSHOT_AT.plusSeconds(1)
		);

		assertThat(service.validateIssue(row, SNAPSHOT_AT).results())
				.anyMatch(result -> result.checkId().equals("issue_record_time_range")
						&& result.status() == ValidationStatus.FAIL);
	}

	@Test
	void 사용시각이_검증시각보다_미래이면_실패한다() {
		CouponIssueRow row = validIssuedRow(
				"USED", LocalDateTime.of(2026, 8, 13, 10, 0), SNAPSHOT_AT.plusSeconds(1), null);

		assertThat(service.validateIssue(row, SNAPSHOT_AT).results())
				.anyMatch(result -> result.checkId().equals("issue_status_fields")
						&& result.status() == ValidationStatus.FAIL);
	}

	@Test
	void 최초_발급과_발급에서_사용으로의_전이는_허용한다() {
		CouponHistoryRow initial = historyRow(null, "ISSUED");
		CouponHistoryRow used = historyRow("ISSUED", "USED");

		assertThat(service.validateHistory(initial, SNAPSHOT_AT).status()).isEqualTo(ValidationStatus.PASS);
		assertThat(service.validateHistory(used, SNAPSHOT_AT).status()).isEqualTo(ValidationStatus.PASS);
	}

	@Test
	void 만료에서_사용으로의_전이는_실패한다() {
		CouponHistoryRow row = historyRow("EXPIRED", "USED");

		RowValidationResponse response = service.validateHistory(row, SNAPSHOT_AT);

		assertThat(response.results()).anyMatch(result -> result.checkId().equals("history_transition_allowed")
				&& result.status() == ValidationStatus.FAIL);
	}

	@Test
	void 선택값인_actor와_eventUid가_없어도_이력_행은_통과한다() {
		CouponHistoryRow row = new CouponHistoryRow(
				1L, 1L, null, "ISSUED", null, null,
				LocalDateTime.of(2026, 8, 12, 11, 0),
				LocalDateTime.of(2026, 8, 12, 11, 0, 1)
		);

		assertThat(service.validateHistory(row, SNAPSHOT_AT).status()).isEqualTo(ValidationStatus.PASS);
	}

	@Test
	void 이력_기록시각이_검증시각보다_미래이면_실패한다() {
		CouponHistoryRow row = new CouponHistoryRow(
				1L, 1L, null, "ISSUED", null, null,
				LocalDateTime.of(2026, 8, 12, 11, 0),
				SNAPSHOT_AT.plusSeconds(1)
		);

		assertThat(service.validateHistory(row, SNAPSHOT_AT).results())
				.anyMatch(result -> result.checkId().equals("history_time_range")
						&& result.status() == ValidationStatus.FAIL);
	}

	private CouponIssueRow validIssuedRow(String status, LocalDateTime validTo,
			LocalDateTime usedAt, LocalDateTime canceledAt) {
		LocalDateTime issuedAt = LocalDateTime.of(2026, 8, 12, 10, 0);
		return new CouponIssueRow(
				1L, 1L, 1L, 1,
				"11111111-1111-1111-1111-111111111111",
				"22222222-2222-2222-2222-222222222222",
				status, issuedAt, issuedAt, validTo, usedAt, canceledAt, issuedAt
		);
	}

	private CouponHistoryRow historyRow(String fromStatus, String toStatus) {
		return new CouponHistoryRow(
				1L, 1L, fromStatus, toStatus,
				"33333333-3333-3333-3333-333333333333", "SYSTEM",
				LocalDateTime.of(2026, 8, 12, 11, 0),
				LocalDateTime.of(2026, 8, 12, 11, 0, 1)
		);
	}
}
