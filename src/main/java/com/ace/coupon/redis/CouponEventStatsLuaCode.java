package com.ace.coupon.redis;

import java.util.Arrays;

public enum CouponEventStatsLuaCode {

	SUCCESS(0),
	CAMPAIGN_NOT_INITIALIZED(1),
	CORRUPTED_STATE(2);

	private final long value;

	CouponEventStatsLuaCode(long value) {
		this.value = value;
	}

	public static CouponEventStatsLuaCode from(long value) {
		return Arrays.stream(values())
				.filter(code -> code.value == value)
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("정의되지 않은 쿠폰 현황 Lua 코드: " + value));
	}
}
