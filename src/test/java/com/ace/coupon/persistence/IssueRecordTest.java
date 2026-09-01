package com.ace.coupon.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ace.coupon.redis.CouponIssueDecision;
import com.ace.coupon.redis.CouponIssueLuaCode;

class IssueRecordTest {

	private static final UUID REQUEST_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
	private static final Instant DECIDED_AT = Instant.ofEpochMilli(1_755_000_000_000L);

	private static Map<String, String> streamFields() {
		Map<String, String> fields = new HashMap<>();
		fields.put(IssueRecord.FIELD_TYPE, IssueRecord.TYPE_ISSUE);
		fields.put(IssueRecord.FIELD_REQUEST_ID, REQUEST_ID.toString());
		fields.put(IssueRecord.FIELD_CAMPAIGN_ID, "1");
		fields.put(IssueRecord.FIELD_USER_ID, "7");
		fields.put(IssueRecord.FIELD_BITMAP_SEGMENT_ID, "0");
		fields.put(IssueRecord.FIELD_BIT_OFFSET, "6");
		fields.put(IssueRecord.FIELD_ISSUE_SEQUENCE, "3");
		fields.put(IssueRecord.FIELD_DECIDED_AT, String.valueOf(DECIDED_AT.toEpochMilli()));
		return fields;
	}

	@Nested
	@DisplayName("필드 검증")
	class Validation {

		@Test
		@DisplayName("requestId가 없으면 거부한다")
		void rejectsMissingRequestId() {
			assertThatThrownBy(() -> new IssueRecord(null, 1L, 1L, 0L, 0L, 1L, DECIDED_AT, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("requestId");
		}

		@Test
		@DisplayName("issueSequence가 0 이하면 거부한다")
		void rejectsNonPositiveSequence() {
			assertThatThrownBy(() -> new IssueRecord(REQUEST_ID, 1L, 1L, 0L, 0L, 0L, DECIDED_AT, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("issueSequence");
		}

		@Test
		@DisplayName("decidedAt이 없으면 거부한다")
		void rejectsMissingDecidedAt() {
			assertThatThrownBy(() -> new IssueRecord(REQUEST_ID, 1L, 1L, 0L, 0L, 1L, null, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("decidedAt");
		}

		@Test
		@DisplayName("빈 messageId는 null로 바꾼다")
		void normalizesBlankMessageId() {
			IssueRecord record = new IssueRecord(REQUEST_ID, 1L, 1L, 0L, 0L, 1L, DECIDED_AT, "  ");

			assertThat(record.messageId()).isNull();
		}
	}

	@Nested
	@DisplayName("fromDecision(SYNC 경로)")
	class FromDecision {

		@Test
		@DisplayName("판정 결과와 Bitmap 위치를 매핑한다")
		void mapsDecision() {
			CouponIssueDecision decision = new CouponIssueDecision(
					CouponIssueLuaCode.ACCEPTED, 3L, 7L, DECIDED_AT);

			IssueRecord record = IssueRecord.fromDecision(1L, 7L, REQUEST_ID, decision);

			assertThat(record.campaignId()).isEqualTo(1L);
			assertThat(record.userId()).isEqualTo(7L);
			assertThat(record.issueSequence()).isEqualTo(3L);
			assertThat(record.decidedAt()).isEqualTo(DECIDED_AT);
			// userId 7 → zero-based 6, 세그먼트 0
			assertThat(record.bitmapSegmentId()).isZero();
			assertThat(record.bitOffset()).isEqualTo(6L);
		}

		@Test
		@DisplayName("Stream을 거치지 않으므로 messageId가 없다")
		void keepsMessageIdNull() {
			CouponIssueDecision decision = new CouponIssueDecision(
					CouponIssueLuaCode.ACCEPTED, 1L, 9L, DECIDED_AT);

			IssueRecord record = IssueRecord.fromDecision(1L, 1L, REQUEST_ID, decision);

			assertThat(record.messageId()).isNull();
		}

		@Test
		@DisplayName("승인이 아닌 판정은 거부한다")
		void rejectsRejectedDecision() {
			CouponIssueDecision decision = new CouponIssueDecision(
					CouponIssueLuaCode.SOLD_OUT, null, 0L, DECIDED_AT);

			assertThatThrownBy(() -> IssueRecord.fromDecision(1L, 1L, REQUEST_ID, decision))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("승인된 판정");
		}
	}

	@Nested
	@DisplayName("fromStreamEntry(RELAY 경로)")
	class FromStreamEntry {

		@Test
		@DisplayName("ISSUE 엔트리를 저장 입력으로 만든다")
		void mapsIssueEntry() {
			Optional<IssueRecord> record = IssueRecord.fromStreamEntry(streamFields(), "1755-0");

			assertThat(record).isPresent();
			assertThat(record.get().requestId()).isEqualTo(REQUEST_ID);
			assertThat(record.get().campaignId()).isEqualTo(1L);
			assertThat(record.get().userId()).isEqualTo(7L);
			assertThat(record.get().issueSequence()).isEqualTo(3L);
			assertThat(record.get().decidedAt()).isEqualTo(DECIDED_AT);
			assertThat(record.get().messageId())
					.hasSize(36)
					.isEqualTo(IssueRecord.messageId(1L, "1755-0"));
		}

		@Test
		@DisplayName("보상 엔트리는 저장 대상이 아니다")
		void skipsCompensateEntry() {
			Map<String, String> fields = streamFields();
			fields.put(IssueRecord.FIELD_TYPE, IssueRecord.TYPE_COMPENSATE);

			assertThat(IssueRecord.fromStreamEntry(fields, "1755-1")).isEmpty();
		}

		@Test
		@DisplayName("type이 없으면 저장 대상이 아니다")
		void skipsUntypedEntry() {
			Map<String, String> fields = streamFields();
			fields.remove(IssueRecord.FIELD_TYPE);

			assertThat(IssueRecord.fromStreamEntry(fields, "1755-2")).isEmpty();
		}

		@Test
		@DisplayName("필드가 누락되면 어떤 필드인지 알려준다")
		void reportsMissingField() {
			Map<String, String> fields = streamFields();
			fields.remove(IssueRecord.FIELD_ISSUE_SEQUENCE);

			assertThatThrownBy(() -> IssueRecord.fromStreamEntry(fields, "1755-3"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining(IssueRecord.FIELD_ISSUE_SEQUENCE);
		}

		@Test
		@DisplayName("숫자가 아닌 필드는 거부한다")
		void rejectsNonNumericField() {
			Map<String, String> fields = streamFields();
			fields.put(IssueRecord.FIELD_USER_ID, "seven");

			assertThatThrownBy(() -> IssueRecord.fromStreamEntry(fields, "1755-4"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining(IssueRecord.FIELD_USER_ID);
		}

		@Test
		@DisplayName("requestId 형식이 틀리면 거부한다")
		void rejectsMalformedRequestId() {
			Map<String, String> fields = streamFields();
			fields.put(IssueRecord.FIELD_REQUEST_ID, "not-a-uuid");

			assertThatThrownBy(() -> IssueRecord.fromStreamEntry(fields, "1755-5"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining(IssueRecord.FIELD_REQUEST_ID);
		}
	}

	@Test
	@DisplayName("messageId는 동일 Stream 위치에 결정적이고 캠페인이 다르면 달라진다")
	void messageIdIsDeterministicAndIncludesCampaign() {
		Map<String, String> first = streamFields();
		Map<String, String> second = streamFields();
		second.put(IssueRecord.FIELD_CAMPAIGN_ID, "2");
		second.put(IssueRecord.FIELD_REQUEST_ID, UUID.randomUUID().toString());

		String firstId = IssueRecord.fromStreamEntry(first, "1755-0").orElseThrow().messageId();
		String secondId = IssueRecord.fromStreamEntry(second, "1755-0").orElseThrow().messageId();

		assertThat(firstId).isEqualTo(IssueRecord.messageId(1L, "1755-0"));
		assertThat(firstId).isEqualTo(IssueRecord.messageId(1L, "1755-0"));
		assertThat(firstId).isNotEqualTo(secondId);
		assertThat(UUID.fromString(firstId).toString()).isEqualTo(firstId);
	}

	@Test
	@DisplayName("messageId가 DB 컬럼 길이를 넘으면 저장 입력에서 거절한다")
	void rejectsTooLongMessageId() {
		assertThatThrownBy(() -> new IssueRecord(
				REQUEST_ID, 1L, 1L, 0L, 0L, 1L, DECIDED_AT, "x".repeat(37)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("36자");
	}
}
