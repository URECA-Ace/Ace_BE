package com.ace.coupon.dto.response;

import java.time.OffsetDateTime;

import com.ace.coupon.enums.CouponEventStatus;

// 수동 마감 결과
public record CouponEventCloseResponse(
		Long eventId,

		// 요청 직후의 실제 회차 상태
		// 확정 대기 건이 남아 있으면 CLOSED 로 안 가고 OPEN / SOLD_OUT 으로 남는다
		CouponEventStatus status,

		// Redis 가 발급을 차단한 시각
		// 이 시점 이후 발급 요청은 전건 거절
		OffsetDateTime closedAt,

		// 파이프라인이 비었는지
		// false 면 남은 건이 확정되는 대로 주기 sweep 이 마감한다
		boolean drained,

		// DB close_at 이 실제로 당겨졌는지
		// Redis 차단과 DB 갱신은 한 트랜잭션이 아니라 앞만 성공할 수 있다
		// false 여도 원래 close_at 이 되면 sweep 이 회수하지만, 지금 닫기가 안 먹었다는 건 알아야 한다
		boolean closeAtAdvanced) {
}
