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
public class CouponIssueFailedEvent {
	private final Long userId;
	private final Long couponEventId;
	private final FailReason reason;
	private final LocalDateTime failedAt;

	public enum FailReason {
		SOLD_OUT,
		ALREADY_ISSUED,
		EVENT_NOT_OPEN,
		UNKNOWN
	}
}