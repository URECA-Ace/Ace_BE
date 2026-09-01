package com.ace.event.coupon;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;

// 짧은 시간에 발급 실패가 몰릴 때 건마다 알림을 쏘는 대신, CouponIssueFailureAggregator가
// 회차+사유별로 모아서 주기적으로 요약 발행하는 이벤트다.
@Getter
@Builder
@AllArgsConstructor
@ToString
public class CouponIssueFailedBatchEvent {
	private final Long couponEventId;
	private final CouponIssueFailedEvent.FailReason reason;
	private final long count;
	private final LocalDateTime windowEndedAt;
}
