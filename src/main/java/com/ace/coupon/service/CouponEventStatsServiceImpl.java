package com.ace.coupon.service;

import java.time.OffsetDateTime;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.response.CouponEventStatsResponse;
import com.ace.coupon.redis.CouponEventStatsSnapshot;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.redis.RedisCouponEventStatsReader;
import com.ace.coupon.repository.CouponEventRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponEventStatsServiceImpl implements CouponEventStatsService {

	private final RedisCouponEventStatsReader statsReader;
	private final CouponEventRepository couponEventRepository;
	private final CouponIssueRedisProperties properties;

	@Override
	public CouponEventStatsResponse findStats(Long eventId) {
		CouponEventStatsSnapshot snapshot;
		try {
			snapshot = statsReader.read(eventId);
		} catch (DataAccessException | IllegalStateException exception) {
			throw unavailable(exception);
		}

		if (snapshot == null) {
			boolean eventExists;
			try {
				eventExists = couponEventRepository.existsById(eventId);
			} catch (DataAccessException exception) {
				throw unavailable(exception);
			}
			if (!eventExists) {
				throw new CouponException(ErrorCode.EVENT_NOT_FOUND);
			}
			throw unavailable(null);
		}

		return new CouponEventStatsResponse(
				snapshot.campaignId(),
				snapshot.totalStock(),
				snapshot.allocatedQuantity(),
				snapshot.remainingStock(),
				snapshot.status(),
				OffsetDateTime.ofInstant(snapshot.observedAt(), properties.zoneId()),
				snapshot.confirmedQuantity(),
				snapshot.pendingQuantity());
	}

	private CouponException unavailable(Throwable cause) {
		return new CouponException(
				ErrorCode.EVENT_STATS_TEMPORARILY_UNAVAILABLE,
				ErrorCode.EVENT_STATS_TEMPORARILY_UNAVAILABLE.getDefaultMessage(),
				cause);
	}
}
