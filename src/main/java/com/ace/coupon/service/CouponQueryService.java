package com.ace.coupon.service;

import java.time.Clock;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ace.coupon.dto.response.CouponSummaryResponse;
import com.ace.coupon.entity.Coupon;
import com.ace.coupon.repository.CouponRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponQueryService {

	private static final int DEFAULT_SIZE = 6;
	private static final int MAX_SIZE = 50;

	private final CouponRepository couponRepository;
	private final Clock clock;

	@Transactional(readOnly = true)
	public List<CouponSummaryResponse> findCoupons(String keyword) {
		return findCoupons(keyword, DEFAULT_SIZE);
	}

	@Transactional(readOnly = true)
	public List<CouponSummaryResponse> findCoupons(String keyword, int size) {
		String normalizedKeyword = keyword == null ? "" : keyword.trim();
		PageRequest pageRequest = PageRequest.of(0, normalizeSize(size));
		List<Coupon> coupons = normalizedKeyword.isEmpty()
				? couponRepository.findAllByOrderByCreatedAtDescIdDesc(pageRequest)
				: couponRepository.searchByCouponName(escapeLike(normalizedKeyword), pageRequest);

		return coupons.stream()
				.map(coupon -> CouponSummaryResponse.from(coupon, clock.getZone()))
				.toList();
	}

	private int normalizeSize(int size) {
		return Math.max(1, Math.min(size, MAX_SIZE));
	}

	private String escapeLike(String keyword) {
		return keyword
				.replace("\\", "\\\\")
				.replace("%", "\\%")
				.replace("_", "\\_");
	}
}
