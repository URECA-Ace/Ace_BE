package com.ace.coupon.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.entity.Coupon;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.repository.CouponEventRepository;
import com.ace.coupon.repository.CouponRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponEventCreationPersistenceService {

	private static final int PER_USER_LIMIT = 1;

	private final CouponRepository couponRepository;
	private final CouponEventRepository couponEventRepository;

	@Transactional
	public CouponEvent create(
			Long couponId,
			Integer round,
			Integer totalStock,
			LocalDateTime openAt,
			LocalDateTime closeAt,
			CouponEventStatus status,
			LocalDateTime now) {
		Coupon coupon = couponRepository.findById(couponId)
				.orElseThrow(() -> new CouponException(ErrorCode.COUPON_NOT_FOUND));

		CouponEvent event = CouponEvent.builder()
				.coupon(coupon)
				.round(round)
				.openAt(openAt)
				.closeAt(closeAt)
				.totalStock(totalStock)
				.remainingStock(totalStock)
				.issuedQuantity(0)
				.perUserLimit(PER_USER_LIMIT)
				.status(status)
				.createdAt(now)
				.updatedAt(now)
				.build();
		return couponEventRepository.saveAndFlush(event);
	}
}
