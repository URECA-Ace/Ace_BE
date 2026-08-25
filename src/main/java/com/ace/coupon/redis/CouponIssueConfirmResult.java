package com.ace.coupon.redis;

import java.util.Arrays;

public enum CouponIssueConfirmResult {

	CONFIRMED_NOW(0),
	ALREADY_CONFIRMED(1),
	REQUEST_NOT_FOUND(2),
	NOT_CONFIRMABLE(3),
	CORRUPTED_STATE(4),
	INVALID_ARGUMENT(5),
	INTERNAL_WRITE_ERROR(6);

	private final long code;

	CouponIssueConfirmResult(long code) {
		this.code = code;
	}

	public static CouponIssueConfirmResult from(long code) {
		return Arrays.stream(values())
				.filter(result -> result.code == code)
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("정의되지 않은 확정 반환 코드: " + code));
	}
}
