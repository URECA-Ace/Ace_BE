package com.ace.coupon.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ace.coupon.dto.response.CouponEventSummaryResponse;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.repository.CouponEventRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponEventQueryService {

	private static final int DEFAULT_SIZE = 6;
	private static final int MAX_SIZE = 50;

	// 상태 필터가 걸렸을 때 한 번에 읽어 볼 회차 수
	private static final int SCAN_PAGE_SIZE = 100;

	// 조건에 맞는 회차가 없을 때 무한 순회를 막는 상한
	private static final int MAX_SCAN_PAGES = 20;

	private final CouponEventRepository couponEventRepository;
	private final Clock clock;

	@Transactional(readOnly = true)
	public List<CouponEventSummaryResponse> findRecentEvents(CouponEventStatus status) {
		return findRecentEvents(status, DEFAULT_SIZE);
	}

	@Transactional(readOnly = true)
	public List<CouponEventSummaryResponse> findRecentEvents(CouponEventStatus status, int size) {
		int requestedSize = Math.max(1, Math.min(size, MAX_SIZE));
		Instant observedAt = clock.instant();

		if (status == null) {
			return couponEventRepository.findRecentWithCoupon(PageRequest.of(0, requestedSize))
					.stream()
					.map(event -> CouponEventSummaryResponse.from(event, clock.getZone(), observedAt))
					.toList();
		}

		// 저장된 상태는 스케줄러가 아직 전환하지 못한 값일 수 있어 DB 에서 바로 거를 수 없다.
		// 시각 기준으로 보정한 상태로 걸러야 하므로, 최신순으로 페이지를 넘기며 필요한 수만큼 채운다.
		// 첫 페이지만 보고 자르면 조건에 맞는 회차가 뒤쪽에 있을 때 조용히 빈 결과가 나간다.
		List<CouponEventSummaryResponse> matched = new ArrayList<>(requestedSize);
		for (int page = 0; page < MAX_SCAN_PAGES && matched.size() < requestedSize; page++) {
			List<CouponEvent> events = couponEventRepository.findRecentWithCoupon(
					PageRequest.of(page, SCAN_PAGE_SIZE));
			if (events.isEmpty()) {
				break;
			}

			for (CouponEvent event : events) {
				CouponEventSummaryResponse summary =
						CouponEventSummaryResponse.from(event, clock.getZone(), observedAt);
				if (summary.status() == status) {
					matched.add(summary);
					if (matched.size() == requestedSize) {
						break;
					}
				}
			}

			if (events.size() < SCAN_PAGE_SIZE) {
				break;
			}
		}
		return List.copyOf(matched);
	}
}
