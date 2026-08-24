package com.ace.coupon.service;

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

	private static final int RECENT_COUPON_LIMIT = 6;

	private final CouponRepository couponRepository;

	@Transactional(readOnly = true)
	public List<CouponSummaryResponse> findCoupons(String keyword) {
		String normalizedKeyword = keyword == null ? "" : keyword.trim();
		List<Coupon> coupons = normalizedKeyword.isEmpty()
				? couponRepository.findAllByOrderByCreatedAtDescIdDesc(
						PageRequest.of(0, RECENT_COUPON_LIMIT))
				: couponRepository.findAllByCouponNameContainingIgnoreCaseOrderByCreatedAtDescIdDesc(
						normalizedKeyword);

		return coupons.stream()
				.map(CouponSummaryResponse::from)
				.toList();
	}
}
