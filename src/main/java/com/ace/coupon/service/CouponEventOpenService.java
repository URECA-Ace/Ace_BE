package com.ace.coupon.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.repository.CouponEventRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponEventOpenService {

	private static final List<CouponEventStatus> AUTO_CLOSE_STATUSES = List.of(
			CouponEventStatus.SCHEDULED,
			CouponEventStatus.OPEN,
			CouponEventStatus.SOLD_OUT);

	private final CouponEventRepository couponEventRepository;

	/**
	 * DB 시각을 기준으로 마감 대상을 먼저 닫고 오픈 대상을 일괄 전환한다.
	 * 마감 조건을 먼저 적용해 경계 시각에는 CLOSED 상태가 우선된다.
	 *
	 * @return 이번 실행에서 OPEN/CLOSED로 전환된 캠페인 수
	 */
	@Transactional
	public TransitionResult transitionDueEvents() {
		int closedCount = couponEventRepository.closeDueEvents(
				AUTO_CLOSE_STATUSES,
				CouponEventStatus.CLOSED);
		int openedCount = couponEventRepository.openDueEvents(
				CouponEventStatus.SCHEDULED,
				CouponEventStatus.OPEN);
		return new TransitionResult(openedCount, closedCount);
	}

	public record TransitionResult(int openedCount, int closedCount) {
	}
}
