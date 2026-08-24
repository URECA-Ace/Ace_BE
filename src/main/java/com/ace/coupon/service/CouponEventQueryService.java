package com.ace.coupon.service;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ace.coupon.dto.response.CouponEventSummaryResponse;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.repository.CouponEventRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponEventQueryService {

	private static final int RECENT_EVENT_LIMIT = 5;

	private final CouponEventRepository couponEventRepository;
	private final CouponIssueRedisProperties properties;

	@Transactional(readOnly = true)
	public List<CouponEventSummaryResponse> findRecentEvents() {
		return couponEventRepository.findRecentWithCoupon(PageRequest.of(0, RECENT_EVENT_LIMIT)).stream()
				.map(event -> CouponEventSummaryResponse.from(event, properties.zoneId()))
				.toList();
	}
}
