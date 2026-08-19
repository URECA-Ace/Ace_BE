package com.ace.coupon.redis;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum CouponEventStatsLuaCode {

	SUCCESS(0),
	CAMPAIGN_NOT_INITIALIZED(1),
	CORRUPTED_STATE(2);

	private static final Map<Long, CouponEventStatsLuaCode> CODE_LOOKUP =
			Arrays.stream(values())
					.collect(Collectors.toUnmodifiableMap(
							code -> code.value,
							Function.identity()));

	private final long value;

	CouponEventStatsLuaCode(long value) {
		this.value = value;
	}

	public static CouponEventStatsLuaCode from(long value) {
		CouponEventStatsLuaCode code = CODE_LOOKUP.get(value);
		if (code == null) {
			throw new IllegalStateException("정의되지 않은 쿠폰 현황 Lua 코드: " + value);
		}
		return code;
	}
}
