package com.ace.coupon.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.ace.coupon.persistence.IssueRecord;
import com.ace.coupon.redis.CouponRedisKeys;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisPendingIssuanceLogReader {

	private final StringRedisTemplate redisTemplate;

	public List<IssueRecord> findAfter(long eventId, long afterSequence, int size) {
		String streamKey = CouponRedisKeys.campaign(eventId).issueStream();
		try {
			List<IssueRecord> records = new ArrayList<>(size);
			Range<String> range = Range.unbounded();

			while (records.size() < size) {
				List<MapRecord<String, String, String>> entries = redisTemplate
						.<String, String>opsForStream()
						.range(streamKey, range, Limit.limit().count(size));
				if (entries == null || entries.isEmpty()) {
					break;
				}

				entries.stream()
						.map(entry -> parse(entry, eventId))
						.flatMap(Optional::stream)
						.filter(record -> record.issueSequence() > afterSequence)
						.limit(size - records.size())
						.forEach(records::add);

				if (entries.size() < size || records.size() >= size) {
					break;
				}
				String lastEntryId = entries.getLast().getId().getValue();
				range = Range.rightUnbounded(Range.Bound.exclusive(lastEntryId));
			}

			return records.stream()
					.sorted(Comparator.comparingLong(IssueRecord::issueSequence))
					.toList();
		} catch (DataAccessException exception) {
			// Redis 관제 조회 장애가 DB 확정 로그까지 가리지 않도록 확정 로그 조회는 계속 제공한다.
			log.warn("Redis 처리 중 발급 로그 조회 실패: eventId={}", eventId, exception);
			return List.of();
		}
	}

	private Optional<IssueRecord> parse(
			MapRecord<String, String, String> entry,
			long eventId) {
		try {
			return IssueRecord.fromStreamEntry(entry.getValue(), entry.getId().getValue())
					.filter(record -> record.campaignId() == eventId);
		} catch (IllegalArgumentException exception) {
			log.warn("관제용 Stream 엔트리 형식 오류: eventId={}, entryId={}",
					eventId, entry.getId(), exception);
			return Optional.empty();
		}
	}
}
