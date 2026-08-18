package com.ace.consistency.rowlevel.service;

import com.ace.consistency.rowlevel.domain.CouponEventStatus;
import com.ace.consistency.rowlevel.domain.CouponIssueStatus;
import com.ace.consistency.rowlevel.domain.HistoryActor;
import com.ace.consistency.rowlevel.domain.ValidationStatus;
import com.ace.consistency.rowlevel.dto.CouponEventRow;
import com.ace.consistency.rowlevel.dto.CouponHistoryRow;
import com.ace.consistency.rowlevel.dto.CouponIssueRow;
import com.ace.consistency.rowlevel.dto.CouponRow;
import com.ace.consistency.rowlevel.dto.RowValidationResponse;
import com.ace.consistency.rowlevel.dto.ValidationResult;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class RowLevelValidationService {

	private static final String CHECK_GROUP = "row_level";
	private final Clock clock;

	public RowLevelValidationService() {
		this(Clock.systemDefaultZone());
	}

	RowLevelValidationService(Clock clock) {
		this.clock = clock;
	}

	public RowValidationResponse validateCoupon(CouponRow row, LocalDateTime snapshotAt) {
		LocalDateTime resolvedSnapshotAt = resolveSnapshotAt(snapshotAt);
		String targetId = valueOf(row.couponId());
		List<ValidationResult> results = new ArrayList<>();

		check(results, "coupon_required_fields", targetId,
				isPositive(row.couponId()) && hasText(row.couponName()) && hasText(row.type())
						&& row.value() != null && row.validHours() != null && row.createdAt() != null,
				"필수 컬럼이 존재하고 coupon_id가 양수", row,
				"쿠폰 행의 필수값을 확인합니다.");

		check(results, "coupon_numeric_range", targetId,
				row.value() != null && row.value() >= 0
						&& row.validHours() != null && row.validHours() > 0,
				"value >= 0, valid_hours > 0",
				"value=" + row.value() + ", valid_hours=" + row.validHours(),
				"쿠폰 값과 유효시간의 범위를 확인합니다.");

		check(results, "coupon_time_range", targetId,
				row.createdAt() != null && !row.createdAt().isAfter(resolvedSnapshotAt),
				"created_at <= snapshot_at",
				"created_at=" + row.createdAt() + ", snapshot_at=" + resolvedSnapshotAt,
				"쿠폰 생성 시각이 검증 기준 시각보다 미래인지 확인합니다.");

		return response("coupon", targetId, resolvedSnapshotAt, results);
	}

	public RowValidationResponse validateEvent(CouponEventRow row, LocalDateTime snapshotAt) {
		LocalDateTime resolvedSnapshotAt = resolveSnapshotAt(snapshotAt);
		String targetId = valueOf(row.eventId());
		List<ValidationResult> results = new ArrayList<>();

		check(results, "event_required_fields", targetId,
				isPositive(row.eventId()) && isPositive(row.couponId()) && isPositive(row.round())
						&& row.openAt() != null && row.closeAt() != null && row.totalStock() != null
						&& row.remainingStock() != null && row.issuedQuantity() != null
						&& row.perUserLimit() != null && row.status() != null
						&& row.createdAt() != null && row.updatedAt() != null,
				"필수 컬럼이 존재하고 식별자/회차가 양수", row,
				"쿠폰 이벤트 필수값을 확인합니다.");

		check(results, "event_status_allowed", targetId, isEnumValue(CouponEventStatus.class, row.status()),
				EnumSet.allOf(CouponEventStatus.class).toString(), row.status(), "이벤트 상태 허용값을 확인합니다.");

		check(results, "event_time_range", targetId,
				row.openAt() != null && row.closeAt() != null && row.openAt().isBefore(row.closeAt())
						&& row.createdAt() != null && row.updatedAt() != null
						&& !row.updatedAt().isBefore(row.createdAt())
						&& !row.updatedAt().isAfter(resolvedSnapshotAt),
				"open_at < close_at, created_at <= updated_at <= snapshot_at",
				row.openAt() + " / " + row.closeAt() + " / " + row.createdAt() + " / " + row.updatedAt(),
				"이벤트 시작·종료 시각의 선후관계를 확인합니다.");

		boolean stockRangeValid = row.totalStock() != null && row.totalStock() >= 0
				&& row.remainingStock() != null && row.remainingStock() >= 0 && row.remainingStock() <= row.totalStock()
				&& row.issuedQuantity() != null && row.issuedQuantity() >= 0 && row.issuedQuantity() <= row.totalStock();
		check(results, "event_numeric_range", targetId, stockRangeValid && row.perUserLimit() != null && row.perUserLimit() > 0,
				"0 <= remaining_stock, issued_quantity <= total_stock; per_user_limit > 0",
				"total=" + row.totalStock() + ", remaining=" + row.remainingStock()
						+ ", issued=" + row.issuedQuantity() + ", limit=" + row.perUserLimit(),
				"한 행 내부 숫자 컬럼의 범위를 확인합니다.");

		return response("coupon_event", targetId, resolvedSnapshotAt, results);
	}

	public RowValidationResponse validateIssue(CouponIssueRow row, LocalDateTime snapshotAt) {
		LocalDateTime resolvedSnapshotAt = resolveSnapshotAt(snapshotAt);
		String targetId = valueOf(row.issueId());
		List<ValidationResult> results = new ArrayList<>();

		check(results, "issue_required_fields", targetId,
				isPositive(row.issueId()) && isPositive(row.eventId()) && isPositive(row.userId())
						&& isPositive(row.issueSequence()) && row.requestId() != null
						&& row.status() != null && row.issuedAt() != null && row.validFrom() != null
						&& row.validTo() != null && row.createdAt() != null,
				"필수 컬럼이 존재하고 ID/발급 순번이 양수", row,
				"쿠폰 발급 행의 필수값을 확인합니다.");

		check(results, "issue_identifier_format", targetId,
				isUuid(row.requestId()) && isOptionalUuid(row.messageId()),
				"request_id는 UUID, message_id는 NULL 또는 UUID",
				"request_id=" + row.requestId() + ", message_id=" + row.messageId(),
				"요청 및 메시지 식별자의 형식을 확인합니다.");

		check(results, "issue_status_allowed", targetId, isEnumValue(CouponIssueStatus.class, row.status()),
				EnumSet.allOf(CouponIssueStatus.class).toString(), row.status(), "쿠폰 상태 허용값을 확인합니다.");

		boolean timeRangeValid = row.issuedAt() != null && row.validFrom() != null && row.validTo() != null
				&& !row.issuedAt().isAfter(row.validFrom()) && row.validFrom().isBefore(row.validTo());
		check(results, "issue_time_range", targetId, timeRangeValid,
				"issued_at <= valid_from < valid_to",
				row.issuedAt() + " / " + row.validFrom() + " / " + row.validTo(),
				"발급 및 유효기간의 논리적 선후관계를 확인합니다.");

		check(results, "issue_record_time_range", targetId,
				row.issuedAt() != null && row.createdAt() != null
						&& !row.createdAt().isBefore(row.issuedAt())
						&& !row.createdAt().isAfter(resolvedSnapshotAt),
				"issued_at <= created_at <= snapshot_at",
				"issued_at=" + row.issuedAt() + ", created_at=" + row.createdAt()
						+ ", snapshot_at=" + resolvedSnapshotAt,
				"발급 발생·기록·검증 기준 시각의 순서를 확인합니다.");

		checkIssueStatusFields(row, targetId, resolvedSnapshotAt, results);
		return response("coupon_issue", targetId, resolvedSnapshotAt, results);
	}

	public RowValidationResponse validateHistory(CouponHistoryRow row, LocalDateTime snapshotAt) {
		LocalDateTime resolvedSnapshotAt = resolveSnapshotAt(snapshotAt);
		String targetId = valueOf(row.historyId());
		List<ValidationResult> results = new ArrayList<>();

		check(results, "history_required_fields", targetId,
				isPositive(row.historyId()) && isPositive(row.issueId()) && row.toStatus() != null
						&& row.occurredAt() != null && row.recordedAt() != null,
				"필수 컬럼이 존재하고 ID가 양수", row,
				"상태 이력 행의 필수값을 확인합니다.");

		check(results, "history_value_allowed", targetId,
				isEnumValue(CouponIssueStatus.class, row.toStatus())
						&& (row.fromStatus() == null || isEnumValue(CouponIssueStatus.class, row.fromStatus()))
						&& (row.actor() == null || isEnumValue(HistoryActor.class, row.actor()))
						&& isOptionalUuid(row.eventUid()),
				"허용된 상태, NULL 또는 허용된 actor, NULL 또는 UUID event_uid",
				"from=" + row.fromStatus() + ", to=" + row.toStatus() + ", actor=" + row.actor()
						+ ", event_uid=" + row.eventUid(),
				"상태, 행위자 및 이벤트 식별자의 허용값을 확인합니다.");

		check(results, "history_transition_allowed", targetId,
				isAllowedTransition(row.fromStatus(), row.toStatus()),
				"NULL→ISSUED 또는 ISSUED→USED/CANCELED/EXPIRED",
				row.fromStatus() + "→" + row.toStatus(),
				"한 이력 행의 상태 전이 규칙을 확인합니다.");

		check(results, "history_time_range", targetId,
				row.occurredAt() != null && row.recordedAt() != null
						&& !row.recordedAt().isBefore(row.occurredAt())
						&& !row.recordedAt().isAfter(resolvedSnapshotAt),
				"occurred_at <= recorded_at <= snapshot_at",
				row.occurredAt() + " / " + row.recordedAt() + " / " + resolvedSnapshotAt,
				"상태 변경 발생·기록·검증 기준 시각의 순서를 확인합니다.");

		return response("coupon_history", targetId, resolvedSnapshotAt, results);
	}

	private void checkIssueStatusFields(CouponIssueRow row, String targetId, LocalDateTime snapshotAt,
			List<ValidationResult> results) {
		CouponIssueStatus status = parseEnum(CouponIssueStatus.class, row.status());
		if (status == null) {
			check(results, "issue_status_fields", targetId, false, "상태별 필드 조합 충족", row.status(),
					"알 수 없는 상태로 상태별 필드를 검증할 수 없습니다.");
			return;
		}

		boolean fieldsValid = switch (status) {
			case ISSUED, EXPIRED -> row.usedAt() == null && row.canceledAt() == null;
			case USED -> row.usedAt() != null && row.canceledAt() == null
					&& isWithin(row.usedAt(), row.validFrom(), row.validTo())
					&& !row.usedAt().isAfter(snapshotAt);
			case CANCELED -> row.canceledAt() != null && row.usedAt() == null
					&& row.issuedAt() != null && !row.canceledAt().isBefore(row.issuedAt())
					&& !row.canceledAt().isAfter(snapshotAt);
		};
		check(results, "issue_status_fields", targetId, fieldsValid,
				"상태와 used_at/canceled_at 조합 일치",
				"status=" + status + ", used_at=" + row.usedAt() + ", canceled_at=" + row.canceledAt(),
				"상태별 필드 조합을 확인합니다.");

		boolean expirationValid = row.validTo() != null && switch (status) {
			case ISSUED -> row.validTo().isAfter(snapshotAt);
			case EXPIRED -> !row.validTo().isAfter(snapshotAt);
			case USED, CANCELED -> true;
		};
		check(results, "issue_expiration_timing", targetId, expirationValid,
				"ISSUED이면 valid_to > snapshot_at, EXPIRED이면 valid_to <= snapshot_at",
				"status=" + status + ", valid_to=" + row.validTo() + ", snapshot_at=" + snapshotAt,
				"개별 쿠폰 행의 만료 처리 시차를 확인합니다.");
	}

	private boolean isAllowedTransition(String from, String to) {
		CouponIssueStatus toStatus = parseEnum(CouponIssueStatus.class, to);
		if (toStatus == null) {
			return false;
		}
		if (from == null) {
			return toStatus == CouponIssueStatus.ISSUED;
		}
		CouponIssueStatus fromStatus = parseEnum(CouponIssueStatus.class, from);
		return fromStatus == CouponIssueStatus.ISSUED
				&& Set.of(CouponIssueStatus.USED, CouponIssueStatus.CANCELED, CouponIssueStatus.EXPIRED)
				.contains(toStatus);
	}

	private RowValidationResponse response(String targetType, String targetId, LocalDateTime snapshotAt,
			List<ValidationResult> results) {
		long failCount = results.stream().filter(result -> result.status() == ValidationStatus.FAIL).count();
		long warningCount = results.stream().filter(result -> result.status() == ValidationStatus.WARNING).count();
		long passCount = results.stream().filter(result -> result.status() == ValidationStatus.PASS).count();
		ValidationStatus status = failCount > 0 ? ValidationStatus.FAIL
				: warningCount > 0 ? ValidationStatus.WARNING : ValidationStatus.PASS;
		return new RowValidationResponse(targetType, targetId, snapshotAt, status, passCount, failCount,
				warningCount, List.copyOf(results));
	}

	private void check(List<ValidationResult> results, String checkId, String targetId, boolean passed,
			String expected, Object actual, String detail) {
		results.add(new ValidationResult(checkId, CHECK_GROUP, targetId,
				passed ? ValidationStatus.PASS : ValidationStatus.FAIL,
				expected, String.valueOf(actual), LocalDateTime.now(clock), detail));
	}

	private LocalDateTime resolveSnapshotAt(LocalDateTime snapshotAt) {
		if (snapshotAt == null) {
			throw new IllegalArgumentException("재현 가능한 검증을 위해 snapshotAt은 필수입니다.");
		}
		return snapshotAt;
	}

	private boolean isPositive(Number value) {
		return value != null && value.longValue() > 0;
	}

	private boolean isUuid(String value) {
		if (value == null) {
			return false;
		}
		try {
			UUID.fromString(value);
			return true;
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private boolean isOptionalUuid(String value) {
		return value == null || isUuid(value);
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private boolean isWithin(LocalDateTime value, LocalDateTime from, LocalDateTime to) {
		return value != null && from != null && to != null && !value.isBefore(from) && !value.isAfter(to);
	}

	private <E extends Enum<E>> boolean isEnumValue(Class<E> type, String value) {
		return parseEnum(type, value) != null;
	}

	private <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
		if (value == null) {
			return null;
		}
		try {
			return Enum.valueOf(type, value);
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private String valueOf(Object value) {
		return value == null ? "UNKNOWN" : String.valueOf(value);
	}
}
