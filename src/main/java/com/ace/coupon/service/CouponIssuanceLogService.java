package com.ace.coupon.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.response.CouponIssuanceLogItemResponse;
import com.ace.coupon.dto.response.CouponIssuanceLogResponse;
import com.ace.coupon.entity.CouponIssue;
import com.ace.coupon.persistence.IssueRecord;
import com.ace.coupon.repository.CouponEventRepository;
import com.ace.coupon.repository.CouponIssueRepository;
import com.ace.user.entity.User;
import com.ace.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponIssuanceLogService {

	private final CouponEventRepository couponEventRepository;
	private final CouponIssueRepository couponIssueRepository;
	private final RedisPendingIssuanceLogReader pendingLogReader;
	private final UserRepository userRepository;
	private final Clock clock;

	@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
	public CouponIssuanceLogResponse findLogs(Long eventId, long afterSequence, int size) {
		int requestedSize = size;
		int databaseCursor = afterSequence >= Integer.MAX_VALUE
				? Integer.MAX_VALUE
				: (int) afterSequence;
		// Redis를 먼저 읽어 릴레이가 DB 커밋 후 Stream을 정리하는 경계에서도 로그가 사라지지 않게 한다.
		List<IssueRecord> processingRecords = pendingLogReader
				.findAfter(eventId, afterSequence, requestedSize + 1);
		List<CouponIssue> queried = couponIssueRepository
				.findByCouponEvent_IdAndIssueSequenceGreaterThanOrderByIssueSequenceAsc(
						eventId,
						databaseCursor,
						PageRequest.of(0, requestedSize + 1));
		if (processingRecords.isEmpty()
				&& queried.isEmpty()
				&& !couponEventRepository.existsById(eventId)) {
			throw new CouponException(ErrorCode.EVENT_NOT_FOUND);
		}
		Map<Long, User> usersById = findUsers(processingRecords);

		TreeMap<Long, CouponIssuanceLogItemResponse> mergedBySequence = new TreeMap<>();
		processingRecords.forEach(record -> mergedBySequence.put(
				record.issueSequence(),
				CouponIssuanceLogItemResponse.processing(
						record, usersById.get(record.userId()), clock.getZone())));
		// 같은 발급 순번이 DB에 존재하면 실제 확정 상태가 PROCESSING보다 우선한다.
		queried.forEach(issue -> mergedBySequence.put(
				issue.getIssueSequence().longValue(),
				CouponIssuanceLogItemResponse.from(issue, clock.getZone())));

		List<CouponIssuanceLogItemResponse> merged = new ArrayList<>(mergedBySequence.values());
		boolean hasMore = merged.size() > requestedSize;
		List<CouponIssuanceLogItemResponse> logs = merged.size() > requestedSize
				? merged.subList(0, requestedSize)
				: merged;
		long nextSequence = logs.isEmpty()
				? afterSequence
				: logs.getLast().issueSequence();

		return new CouponIssuanceLogResponse(eventId, logs, nextSequence, hasMore);
	}

	private Map<Long, User> findUsers(List<IssueRecord> records) {
		LinkedHashSet<Long> userIds = records.stream()
				.map(IssueRecord::userId)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		if (userIds.isEmpty()) {
			return Map.of();
		}
		return userRepository.findAllById(userIds).stream()
				.collect(Collectors.toMap(User::getId, Function.identity()));
	}
}
