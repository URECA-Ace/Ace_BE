package com.ace.coupon.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.entity.CampaignRedisInitialization;
import com.ace.coupon.entity.Coupon;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.repository.CampaignRedisInitializationRepository;
import com.ace.coupon.repository.CouponEventRepository;
import com.ace.coupon.repository.CouponRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponEventCreationPersistenceService {

	private static final int PER_USER_LIMIT = 1;

	private final CouponRepository couponRepository;
	private final CouponEventRepository couponEventRepository;
	private final CampaignRedisInitializationRepository initializationRepository;

	@Transactional
	public CouponEvent createOrReuse(
			Long couponId,
			Integer round,
			Integer totalStock,
			LocalDateTime openAt,
			LocalDateTime closeAt,
			CouponEventStatus status,
			LocalDateTime now) {
		Coupon coupon = couponRepository.findByIdForUpdate(couponId)
				.orElseThrow(() -> new CouponException(ErrorCode.COUPON_NOT_FOUND));
		return couponEventRepository.findByCoupon_IdAndRound(couponId, round)
				.map(event -> reuseWhenSameConfiguration(
						event, totalStock, openAt, closeAt))
				.orElseGet(() -> save(
						coupon, round, totalStock, openAt, closeAt, status, now));
	}

	@Transactional
	public CouponEvent createNextRoundOrReuse(
			Long couponId,
			Integer totalStock,
			LocalDateTime openAt,
			LocalDateTime closeAt,
			CouponEventStatus status,
			LocalDateTime now) {
		Coupon coupon = couponRepository.findByIdForUpdate(couponId)
				.orElseThrow(() -> new CouponException(ErrorCode.COUPON_NOT_FOUND));
		var existing = couponEventRepository
				.findFirstByCoupon_IdAndTotalStockAndOpenAtAndCloseAtAndPerUserLimitOrderByIdAsc(
						couponId, totalStock, openAt, closeAt, PER_USER_LIMIT);
		if (existing.isPresent()) {
			return existing.get();
		}
		Integer nextRound = couponEventRepository.findMaxRoundByCouponId(couponId) + 1;
		return save(coupon, nextRound, totalStock, openAt, closeAt, status, now);
	}

	private CouponEvent reuseWhenSameConfiguration(
			CouponEvent existing,
			Integer totalStock,
			LocalDateTime openAt,
			LocalDateTime closeAt) {
		if (!existing.getTotalStock().equals(totalStock)
				|| existing.getPerUserLimit() != PER_USER_LIMIT
				|| !existing.getOpenAt().equals(openAt)
				|| !existing.getCloseAt().equals(closeAt)) {
			throw new CouponException(ErrorCode.EVENT_CONFIGURATION_CONFLICT);
		}
		return existing;
	}

	private CouponEvent save(
			Coupon coupon,
			Integer round,
			Integer totalStock,
			LocalDateTime openAt,
			LocalDateTime closeAt,
			CouponEventStatus status,
			LocalDateTime now) {
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
		CouponEvent saved = couponEventRepository.saveAndFlush(event);
		initializationRepository.save(CampaignRedisInitialization.pending(saved.getId(), now));
		return saved;
	}
}
