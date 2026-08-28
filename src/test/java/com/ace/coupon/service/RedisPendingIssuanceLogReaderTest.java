package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.ace.coupon.persistence.IssueRecord;
import com.ace.coupon.redis.CouponRedisKeys;

@SuppressWarnings("unchecked")
class RedisPendingIssuanceLogReaderTest {

	private static final long EVENT_ID = 60L;
	private static final String STREAM_KEY = CouponRedisKeys.campaign(EVENT_ID).issueStream();

	private StreamOperations<String, String, String> streamOperations;
	private RedisPendingIssuanceLogReader reader;

	@BeforeEach
	void setUp() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		streamOperations = mock(StreamOperations.class);
		given(redisTemplate.<String, String>opsForStream()).willReturn(streamOperations);
		reader = new RedisPendingIssuanceLogReader(redisTemplate);
	}

	@Test
	@DisplayName("최신 N건이 아니라 Stream을 전진하며 순번 커서 다음 처리 로그를 채운다")
	void scansForwardUntilRecordsAfterSequenceAreFilled() {
		given(streamOperations.range(eq(STREAM_KEY), any(Range.class), any(Limit.class)))
				.willReturn(
						List.of(entry("1-0", 1), entry("2-0", 2)),
						List.of(entry("3-0", 3), entry("4-0", 4)));

		List<IssueRecord> result = reader.findAfter(EVENT_ID, 2, 2);

		assertThat(result).extracting(IssueRecord::issueSequence)
				.containsExactly(3L, 4L);
		verify(streamOperations, times(2))
				.range(eq(STREAM_KEY), any(Range.class), any(Limit.class));
	}

	private MapRecord<String, String, String> entry(String entryId, long sequence) {
		Map<String, String> fields = Map.of(
				IssueRecord.FIELD_TYPE, IssueRecord.TYPE_ISSUE,
				IssueRecord.FIELD_REQUEST_ID, UUID.randomUUID().toString(),
				IssueRecord.FIELD_CAMPAIGN_ID, Long.toString(EVENT_ID),
				IssueRecord.FIELD_USER_ID, Long.toString(sequence),
				IssueRecord.FIELD_BITMAP_SEGMENT_ID, "0",
				IssueRecord.FIELD_BIT_OFFSET, Long.toString(sequence - 1),
				IssueRecord.FIELD_ISSUE_SEQUENCE, Long.toString(sequence),
				IssueRecord.FIELD_DECIDED_AT, Long.toString(Instant.parse(
						"2026-08-27T01:00:00Z").toEpochMilli()));
		return MapRecord.create(STREAM_KEY, fields).withId(RecordId.of(entryId));
	}
}
