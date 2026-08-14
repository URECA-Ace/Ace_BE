package com.ace.coupon.redis;

import java.util.Arrays;

public enum CouponIssueCompensationResult {

	COMPENSATED(0),
	REQUEST_NOT_FOUND(1),
	NOT_COMPENSABLE(2),
	CORRUPTED_STATE(3);

	private final long code;

	CouponIssueCompensationResult(long code) {
		this.code = code;
	}

	public static CouponIssueCompensationResult from(long code) {
		return Arrays.stream(values())
				.filter(result -> result.code == code)
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("정의되지 않은 보상 반환 코드: " + code));
	}
}
