package com.ace.coupon.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ace.coupon.dto.request.CouponCreateRequest;
import com.ace.coupon.dto.response.CouponCreateResponse;
import com.ace.coupon.entity.Coupon;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.repository.CouponRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponCreationService {

	private final CouponRepository couponRepository;
	private final CouponIssueRedisProperties properties;

	@Transactional
	public CouponCreateResponse create(CouponCreateRequest request) {
		Coupon coupon = Coupon.builder()
				.couponName(request.couponName().trim())
				.type(request.type().trim())
				.value(request.value())
				.validHours(request.validHours())
				.createdAt(LocalDateTime.now(properties.zoneId()))
				.build();
		return CouponCreateResponse.from(couponRepository.save(coupon));
	}
}
