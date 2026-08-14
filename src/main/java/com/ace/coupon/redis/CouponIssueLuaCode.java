package com.ace.coupon.redis;

import java.util.Arrays;

import com.ace.coupon.enums.IssueRequestStatus;

public enum CouponIssueLuaCode {

	ACCEPTED(0, IssueRequestStatus.ACCEPTED),
	SOLD_OUT(1, IssueRequestStatus.REJECTED_SOLD_OUT),
	ALREADY_ISSUED(2, IssueRequestStatus.REJECTED_DUPLICATE),
	EVENT_NOT_OPEN(3, IssueRequestStatus.REJECTED_NOT_OPEN),
	EVENT_CLOSED(4, IssueRequestStatus.REJECTED_CLOSED),
	CAMPAIGN_NOT_INITIALIZED(5, null),
	IDEMPOTENCY_CONFLICT(6, null),
	CORRUPTED_STATE(7, null),
	PERSISTENCE_FAILED(8, IssueRequestStatus.COMPENSATED);

	private final long value;
	private final IssueRequestStatus requestStatus;

	CouponIssueLuaCode(long value, IssueRequestStatus requestStatus) {
		this.value = value;
		this.requestStatus = requestStatus;
	}

	public long value() {
		return value;
	}

	public IssueRequestStatus requestStatus() {
		return requestStatus;
	}

	public static CouponIssueLuaCode from(long value) {
		return Arrays.stream(values())
				.filter(code -> code.value == value)
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("정의되지 않은 Lua 반환 코드: " + value));
	}
}
