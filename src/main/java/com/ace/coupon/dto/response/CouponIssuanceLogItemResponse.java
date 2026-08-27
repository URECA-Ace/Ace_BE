package com.ace.coupon.dto.response;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import com.ace.coupon.entity.CouponIssue;

public record CouponIssuanceLogItemResponse(
		Long userId,
		Integer issueSequence,
		OffsetDateTime issuedAt,
		OffsetDateTime confirmedAt) {

	public static CouponIssuanceLogItemResponse from(CouponIssue issue, ZoneId zoneId) {
		return new CouponIssuanceLogItemResponse(
				issue.getUser().getId(),
				issue.getIssueSequence(),
				issue.getIssuedAt().atZone(zoneId).toOffsetDateTime(),
				issue.getCreatedAt().atZone(zoneId).toOffsetDateTime());
	}
}
