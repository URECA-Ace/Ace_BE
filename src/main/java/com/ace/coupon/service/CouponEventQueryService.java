package com.ace.coupon.service;

import java.time.Clock;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ace.coupon.dto.response.CouponEventSummaryResponse;
import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.repository.CouponEventRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponEventQueryService {

	private static final int DEFAULT_SIZE = 6;
	private static final int MAX_SIZE = 50;

	private final CouponEventRepository couponEventRepository;
	private final Clock clock;

	@Transactional(readOnly = true)
	public List<CouponEventSummaryResponse> findRecentEvents(CouponEventStatus status) {
		return findRecentEvents(status, DEFAULT_SIZE);
	}

	@Transactional(readOnly = true)
	public List<CouponEventSummaryResponse> findRecentEvents(CouponEventStatus status, int size) {
		int requestedSize = Math.max(1, Math.min(size, MAX_SIZE));
		int querySize = status == null ? requestedSize : MAX_SIZE;
		var events = couponEventRepository.findRecentWithCoupon(PageRequest.of(0, querySize));
		var observedAt = clock.instant();
		return events.stream()
				.map(event -> CouponEventSummaryResponse.from(event, clock.getZone(), observedAt))
				.filter(event -> status == null || event.status() == status)
				.limit(requestedSize)
				.toList();
	}
}
