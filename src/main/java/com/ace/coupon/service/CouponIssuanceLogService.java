package com.ace.coupon.service;

import java.time.Clock;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.response.CouponIssuanceLogItemResponse;
import com.ace.coupon.dto.response.CouponIssuanceLogResponse;
import com.ace.coupon.entity.CouponIssue;
import com.ace.coupon.repository.CouponEventRepository;
import com.ace.coupon.repository.CouponIssueRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponIssuanceLogService {

	private static final int MAX_SIZE = 500;

	private final CouponEventRepository couponEventRepository;
	private final CouponIssueRepository couponIssueRepository;
	private final Clock clock;

	@Transactional(readOnly = true)
	public CouponIssuanceLogResponse findLogs(Long eventId, int afterSequence, int size) {
		if (!couponEventRepository.existsById(eventId)) {
			throw new CouponException(ErrorCode.EVENT_NOT_FOUND);
		}

		int requestedSize = Math.max(1, Math.min(size, MAX_SIZE));
		List<CouponIssue> queried = couponIssueRepository
				.findByCouponEvent_IdAndIssueSequenceGreaterThanOrderByIssueSequenceAsc(
						eventId,
						afterSequence,
						PageRequest.of(0, requestedSize + 1));
		boolean hasMore = queried.size() > requestedSize;
		List<CouponIssue> visibleIssues = hasMore
				? queried.subList(0, requestedSize)
				: queried;
		List<CouponIssuanceLogItemResponse> logs = visibleIssues.stream()
				.map(issue -> CouponIssuanceLogItemResponse.from(issue, clock.getZone()))
				.toList();
		int nextSequence = visibleIssues.isEmpty()
				? afterSequence
				: visibleIssues.getLast().getIssueSequence();

		return new CouponIssuanceLogResponse(eventId, logs, nextSequence, hasMore);
	}
}
