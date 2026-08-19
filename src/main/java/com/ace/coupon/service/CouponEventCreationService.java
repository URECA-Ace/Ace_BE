package com.ace.coupon.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.request.CouponEventCreateRequest;
import com.ace.coupon.dto.response.CouponEventCreateResponse;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.redis.CampaignInitializationResult;
import com.ace.coupon.redis.CampaignRedisInitializer;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.repository.CouponEventRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponEventCreationService {

	private final CouponEventCreationPersistenceService persistenceService;
	private final CouponEventRepository couponEventRepository;
	private final CampaignRedisInitializer campaignRedisInitializer;
	private final CouponIssueRedisProperties properties;

	public CouponEventCreateResponse create(Long couponId, CouponEventCreateRequest request) {
		Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		NormalizedConfiguration configuration = normalize(request, properties.zoneId(), now);
		CouponEvent event = persistOrReuse(couponId, request, configuration, now);

		initializeRedis(event);
		return CouponEventCreateResponse.from(event, couponId, properties.zoneId());
	}

	private CouponEvent persistOrReuse(
			Long couponId,
			CouponEventCreateRequest request,
			NormalizedConfiguration configuration,
			Instant now) {
		Optional<CouponEvent> existing = couponEventRepository
				.findByCoupon_IdAndRound(couponId, request.round());
		if (existing.isPresent()) {
			return reuseWhenSameConfiguration(existing.get(), request.totalStock(), configuration);
		}

		try {
			return persistenceService.create(
					couponId,
					request.round(),
					request.totalStock(),
					configuration.openAt(),
					configuration.closeAt(),
					configuration.status(),
					LocalDateTime.ofInstant(now, properties.zoneId()));
		} catch (DataIntegrityViolationException exception) {
			CouponEvent concurrentlyCreated = couponEventRepository
					.findByCoupon_IdAndRound(couponId, request.round())
					.orElseThrow(() -> exception);
			return reuseWhenSameConfiguration(
					concurrentlyCreated, request.totalStock(), configuration);
		}
	}

	private CouponEvent reuseWhenSameConfiguration(
			CouponEvent existing,
			Integer totalStock,
			NormalizedConfiguration configuration) {
		if (!sameConfiguration(existing, totalStock, configuration)) {
			throw new CouponException(ErrorCode.EVENT_CONFIGURATION_CONFLICT);
		}
		return existing;
	}

	private NormalizedConfiguration normalize(
			CouponEventCreateRequest request,
			ZoneId zoneId,
			Instant now) {
		Instant openAt = request.openAt().toInstant().truncatedTo(ChronoUnit.MILLIS);
		Instant closeAt = request.closeAt().toInstant().truncatedTo(ChronoUnit.MILLIS);
		if (!openAt.isBefore(closeAt)) {
			throw new CouponException(ErrorCode.INVALID_REQUEST, "openAt은 closeAt보다 빨라야 합니다.");
		}
		if (!closeAt.isAfter(now)) {
			throw new CouponException(ErrorCode.INVALID_REQUEST, "closeAt은 현재 시각보다 이후여야 합니다.");
		}

		CouponEventStatus status = openAt.isAfter(now)
				? CouponEventStatus.SCHEDULED
				: CouponEventStatus.OPEN;
		return new NormalizedConfiguration(
				LocalDateTime.ofInstant(openAt, zoneId),
				LocalDateTime.ofInstant(closeAt, zoneId),
				status);
	}

	private boolean sameConfiguration(
			CouponEvent existing,
			Integer totalStock,
			NormalizedConfiguration configuration) {
		return existing.getTotalStock().equals(totalStock)
				&& existing.getPerUserLimit() == 1
				&& existing.getOpenAt().equals(configuration.openAt())
				&& existing.getCloseAt().equals(configuration.closeAt());
	}

	private void initializeRedis(CouponEvent event) {
		CampaignInitializationResult result;
		try {
			result = campaignRedisInitializer.initialize(event);
		} catch (DataAccessException | IllegalStateException | IllegalArgumentException exception) {
			throw initializationUnavailable(exception);
		}

		switch (result) {
			case INITIALIZED, ALREADY_INITIALIZED -> {
				return;
			}
			case CONFIGURATION_CONFLICT, INVALID_CONFIGURATION, INTERNAL_WRITE_ERROR ->
					throw initializationUnavailable(null);
		}
	}

	private CouponException initializationUnavailable(Throwable cause) {
		return new CouponException(
				ErrorCode.CAMPAIGN_INITIALIZATION_TEMPORARILY_UNAVAILABLE,
				ErrorCode.CAMPAIGN_INITIALIZATION_TEMPORARILY_UNAVAILABLE.getDefaultMessage(),
				cause);
	}

	private record NormalizedConfiguration(
			LocalDateTime openAt,
			LocalDateTime closeAt,
			CouponEventStatus status) {
	}
}
