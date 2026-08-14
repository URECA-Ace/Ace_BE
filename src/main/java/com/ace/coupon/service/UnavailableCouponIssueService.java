package com.ace.coupon.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.response.CouponIssueAcceptedResponse;

/**
 * Redis + Lua 기반 발급 판정 구현이 연결되기 전까지 애플리케이션 기동을 보장하는 임시 구현체.
 * 실제 판정 없이 성공 응답을 만들지 않고 호출자에게 일시적 사용 불가를 명확히 알린다.
 */
@Service
public class UnavailableCouponIssueService implements CouponIssueService {

	@Override
	public CouponIssueAcceptedResponse issue(Long eventId, Long userId, UUID idempotencyKey) {
		throw new CouponException(ErrorCode.ISSUE_TEMPORARILY_UNAVAILABLE);
	}
}
