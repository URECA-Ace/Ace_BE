package com.ace.coupon.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.repository.CouponEventRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponEventOpenService {

	private final CouponEventRepository couponEventRepository;

	/**
	 * DB 시각을 기준으로 오픈 대상 캠페인을 일괄 전환한다.
	 *
	 * @return 이번 실행에서 SCHEDULED에서 OPEN으로 전환된 캠페인 수
	 */
	@Transactional
	public int openDueEvents() {
		return couponEventRepository.openDueEvents(
				CouponEventStatus.SCHEDULED,
				CouponEventStatus.OPEN);
	}
}
