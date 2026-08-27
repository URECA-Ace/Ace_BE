package com.ace.coupon.dto.response;

import java.time.OffsetDateTime;

import com.ace.coupon.enums.CouponEventStatus;

/**
 * 수동 마감 결과.
 *
 * <p>{@code closedAt} 은 Redis 가 발급을 차단한 시각이다. 이 시점 이후의 발급 요청은 전건 거절된다.
 *
 * <p>{@code status} 는 요청 처리 직후의 실제 회차 상태다. 아직 저장 중인 발급 건이 남아 있으면
 * {@code CLOSED} 로 넘어가지 않고 {@code OPEN} 또는 {@code SOLD_OUT} 으로 남는다.
 * {@code CLOSED} 는 검증팀의 Drain 조건이라 파이프라인이 빈 뒤에만 찍혀야 하기 때문이다.
 * 이 경우 {@code drained} 가 {@code false} 이고, 남은 건이 확정되는 대로 주기 sweep 이 마감한다.
 */
public record CouponEventCloseResponse(
		Long eventId,
		CouponEventStatus status,
		OffsetDateTime closedAt,
		boolean drained) {
}
