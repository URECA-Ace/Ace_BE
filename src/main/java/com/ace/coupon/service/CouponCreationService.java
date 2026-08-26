package com.ace.coupon.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ace.coupon.dto.request.CouponCreateRequest;
import com.ace.coupon.dto.response.CouponSummaryResponse;
import com.ace.coupon.entity.Coupon;
import com.ace.coupon.repository.CouponRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponCreationService {

	private final CouponRepository couponRepository;
	private final Clock clock;

	@Transactional
	public CouponSummaryResponse create(CouponCreateRequest request) {
		Coupon coupon = Coupon.builder()
				.couponName(request.couponName().trim())
				.type(request.type().name())
				.value(request.value())
				.validHours(request.validHours())
				.createdAt(LocalDateTime.now(clock))
				.build();
		return CouponSummaryResponse.from(couponRepository.save(coupon), clock.getZone());
	}
}
