package com.ace.coupon.redis;

import java.util.Arrays;

public enum CampaignInitializationResult {

	INITIALIZED(0),
	ALREADY_INITIALIZED(1),
	CONFIGURATION_CONFLICT(2),
	INVALID_CONFIGURATION(3),
	INTERNAL_WRITE_ERROR(4);

	private final long code;

	CampaignInitializationResult(long code) {
		this.code = code;
	}

	public static CampaignInitializationResult from(long code) {
		return Arrays.stream(values())
				.filter(result -> result.code == code)
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("정의되지 않은 초기화 반환 코드: " + code));
	}
}
