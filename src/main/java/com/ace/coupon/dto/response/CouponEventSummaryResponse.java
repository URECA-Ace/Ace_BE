package com.ace.coupon.dto.response;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.enums.CouponEventStatus;

public record CouponEventSummaryResponse(
		Long eventId,
		Long couponId,
		String couponName,
		Integer round,
		Integer totalStock,
		Integer remainingStock,
		CouponEventStatus status,
		OffsetDateTime openAt,
		OffsetDateTime closeAt,
		OffsetDateTime statusChangedAt) {

	public static CouponEventSummaryResponse from(CouponEvent event, ZoneId zoneId, Instant observedAt) {
		Instant openAt = event.getOpenAt().atZone(zoneId).toInstant();
		Instant closeAt = event.getCloseAt().atZone(zoneId).toInstant();
		CouponEventStatus effectiveStatus = effectiveStatus(event.getStatus(), observedAt, openAt, closeAt);
		LocalDateTime statusChangedAt = effectiveStatus == event.getStatus()
				? event.getUpdatedAt()
				: effectiveStatus == CouponEventStatus.OPEN ? event.getOpenAt() : event.getCloseAt();

		return new CouponEventSummaryResponse(
				event.getId(),
				event.getCoupon().getId(),
				event.getCoupon().getCouponName(),
				event.getRound(),
				event.getTotalStock(),
				event.getRemainingStock(),
				effectiveStatus,
				event.getOpenAt().atZone(zoneId).toOffsetDateTime(),
				event.getCloseAt().atZone(zoneId).toOffsetDateTime(),
				statusChangedAt.atZone(zoneId).toOffsetDateTime());
	}

	private static CouponEventStatus effectiveStatus(
			CouponEventStatus storedStatus,
			Instant observedAt,
			Instant openAt,
			Instant closeAt) {
		if (storedStatus == CouponEventStatus.CLOSED) {
			return CouponEventStatus.CLOSED;
		}
		if (observedAt.isBefore(openAt)) {
			return CouponEventStatus.SCHEDULED;
		}
		if (!observedAt.isBefore(closeAt)) {
			return CouponEventStatus.CLOSED;
		}
		if (storedStatus == CouponEventStatus.SOLD_OUT) {
			return CouponEventStatus.SOLD_OUT;
		}
		return CouponEventStatus.OPEN;
	}
}
