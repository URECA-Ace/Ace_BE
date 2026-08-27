package com.ace.coupon.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.request.CouponEventCreateRequest;
import com.ace.coupon.dto.response.CouponEventCreateResponse;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.enums.CouponEventStatus;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponEventCreationService {

	private final CouponEventCreationPersistenceService persistenceService;
	private final CampaignAdminService campaignAdminService;
	private final Clock clock;

	public CouponEventCreateResponse create(Long couponId, CouponEventCreateRequest request) {
		Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
		NormalizedConfiguration configuration = normalize(request, clock.getZone(), now);
		CouponEvent event = request.round() == null
				? persistNextRound(couponId, request, configuration, now)
				: persistOrReuse(couponId, request, configuration, now);

		initializeRedis(event);
		return CouponEventCreateResponse.from(event, couponId, clock.getZone());
	}

	private CouponEvent persistNextRound(
			Long couponId,
			CouponEventCreateRequest request,
			NormalizedConfiguration configuration,
			Instant now) {
		return persistenceService.createNextRoundOrReuse(
				couponId,
				request.totalStock(),
				configuration.openAt(),
				configuration.closeAt(),
				configuration.status(),
				LocalDateTime.ofInstant(now, clock.getZone()));
	}

	private CouponEvent persistOrReuse(
			Long couponId,
			CouponEventCreateRequest request,
			NormalizedConfiguration configuration,
			Instant now) {
		return persistenceService.createOrReuse(
					couponId,
					request.round(),
					request.totalStock(),
					configuration.openAt(),
					configuration.closeAt(),
					configuration.status(),
					LocalDateTime.ofInstant(now, clock.getZone()));
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

	private void initializeRedis(CouponEvent event) {
		try {
			campaignAdminService.initialize(event);
		} catch (CouponException | DataAccessException | IllegalStateException | IllegalArgumentException exception) {
			throw initializationUnavailable(exception);
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
