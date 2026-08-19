package com.ace.coupon.persistence;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ace.coupon.redis.CouponIssueDecision;
import com.ace.coupon.redis.CouponRedisKeys;

// 저장 계층 입력 계약
public record IssueRecord(
		UUID requestId,
		long campaignId,
		long userId,
		long bitmapSegmentId,
		long bitOffset,
		long issueSequence,
		Instant decidedAt,
		String messageId) {

	// coupon-issue.lua 의 XADD 필드명
	// 여기서만 정의하고 소비 계층에서 사용할 때 이 것을 사용
	public static final String FIELD_TYPE = "type";
	public static final String FIELD_REQUEST_ID = "requestId";
	public static final String FIELD_CAMPAIGN_ID = "campaignId";
	public static final String FIELD_USER_ID = "userId";
	public static final String FIELD_BITMAP_SEGMENT_ID = "bitmapSegmentId";
	public static final String FIELD_BIT_OFFSET = "bitOffset";
	public static final String FIELD_ISSUE_SEQUENCE = "issueSequence";
	public static final String FIELD_DECIDED_AT = "decidedAt";

	public static final String TYPE_ISSUE = "ISSUE";
	public static final String TYPE_COMPENSATE = "COMPENSATE";

	public IssueRecord {
		if (requestId == null) {
			throw new IllegalArgumentException("requestId가 필요합니다.");
		}
		if (campaignId <= 0) {
			throw new IllegalArgumentException("campaignId는 양수여야 합니다.");
		}
		if (userId <= 0) {
			throw new IllegalArgumentException("userId는 양수여야 합니다.");
		}
		if (bitmapSegmentId < 0) {
			throw new IllegalArgumentException("bitmapSegmentId는 음수일 수 없습니다.");
		}
		if (bitOffset < 0) {
			throw new IllegalArgumentException("bitOffset은 음수일 수 없습니다.");
		}
		if (issueSequence <= 0) {
			throw new IllegalArgumentException("issueSequence는 양수여야 합니다.");
		}
		if (decidedAt == null) {
			throw new IllegalArgumentException("decidedAt이 필요합니다.");
		}
		// uk_coupon_issue_message_id 가 UNIQUE 라 빈 문자열은 두 번째 저장에서 충돌한다
		if (messageId != null && messageId.isBlank()) {
			messageId = null;
		}
	}

	// SYNC 경로(Lua 판정 결과를 그대로 저장 입력으로)
	public static IssueRecord fromDecision(
			long campaignId,
			long userId,
			UUID requestId,
			CouponIssueDecision decision) {
		if (decision == null) {
			throw new IllegalArgumentException("판정 결과가 필요합니다.");
		}
		if (!decision.accepted()) {
			throw new IllegalArgumentException("승인된 판정만 저장할 수 있습니다: " + decision.code());
		}

		CouponRedisKeys.BitmapLocation bitmap = CouponRedisKeys.campaign(campaignId).bitmap(userId);
		return new IssueRecord(
				requestId,
				campaignId,
				userId,
				bitmap.segment(),
				bitmap.offset(),
				decision.issueSequence(),
				decision.decidedAt(),
				null);
	}

	// RELAY 경로(Stream 엔트리를 저장 입력으로)
	// Stream 엔트리 ID 는 그 Stream 안에서만 유일한데 message_id 는 전역 UNIQUE  = 캠페인 식별자를 붙여야 다른 캠페인과 충돌하지 않는다
	public static Optional<IssueRecord> fromStreamEntry(Map<String, String> fields, String entryId) {
		if (fields == null) {
			throw new IllegalArgumentException("Stream 엔트리 필드가 필요합니다.");
		}
		if (!TYPE_ISSUE.equals(fields.get(FIELD_TYPE))) {
			return Optional.empty();
		}

		return Optional.of(new IssueRecord(
				uuid(fields, FIELD_REQUEST_ID),
				number(fields, FIELD_CAMPAIGN_ID),
				number(fields, FIELD_USER_ID),
				number(fields, FIELD_BITMAP_SEGMENT_ID),
				number(fields, FIELD_BIT_OFFSET),
				number(fields, FIELD_ISSUE_SEQUENCE),
				Instant.ofEpochMilli(number(fields, FIELD_DECIDED_AT)),
				messageId(number(fields, FIELD_CAMPAIGN_ID), entryId)));
	}

	// campaignId-entryId
	// 전역 UNIQUE 인 message_id 컬럼에 그대로 들어간다
	public static String messageId(long campaignId, String entryId) {
		if (entryId == null || entryId.isBlank()) {
			throw new IllegalArgumentException("Stream 엔트리 식별자가 필요합니다.");
		}
		return campaignId + "-" + entryId;
	}

	private static String required(Map<String, String> fields, String name) {
		String value = fields.get(name);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("Stream 엔트리 필드가 없습니다: " + name);
		}
		return value;
	}

	private static UUID uuid(Map<String, String> fields, String name) {
		try {
			return UUID.fromString(required(fields, name));
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Stream 엔트리 필드 형식이 올바르지 않습니다: " + name, exception);
		}
	}

	private static long number(Map<String, String> fields, String name) {
		try {
			return Long.parseLong(required(fields, name));
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException("Stream 엔트리 숫자 필드가 올바르지 않습니다: " + name, exception);
		}
	}
}
