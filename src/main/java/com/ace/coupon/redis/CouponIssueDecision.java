package com.ace.coupon.redis;

import java.time.Instant;

public record CouponIssueDecision(
		CouponIssueLuaCode code,
		Long issueSequence,
		Long remainingStock,
		Instant decidedAt) {

	public boolean accepted() {
		return code == CouponIssueLuaCode.ACCEPTED;
	}
}
