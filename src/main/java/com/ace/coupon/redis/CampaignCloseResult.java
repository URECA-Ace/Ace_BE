package com.ace.coupon.redis;

public enum CampaignCloseResult {
	CLOSED(0),
	ALREADY_CLOSED(1),
	NOT_INITIALIZED(2),
	INVALID_STATE(3),
	CORRUPTED_STATE(4),
	INTERNAL_WRITE_ERROR(5);

	private final long code;

	CampaignCloseResult(long code) {
		this.code = code;
	}

	public static CampaignCloseResult from(long code) {
		for (CampaignCloseResult result : values()) {
			if (result.code == code) {
				return result;
			}
		}
		throw new IllegalArgumentException("알 수 없는 캠페인 마감 결과입니다: " + code);
	}
}
