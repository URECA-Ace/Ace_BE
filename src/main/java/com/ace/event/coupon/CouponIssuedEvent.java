package com.ace.event.coupon;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
@ToString
public class CouponIssuedEvent {
	private final Long userId;
	private final Long couponEventId;
	private final LocalDateTime issuedAt;
}