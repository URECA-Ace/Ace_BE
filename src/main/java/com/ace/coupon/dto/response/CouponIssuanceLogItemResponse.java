package com.ace.coupon.dto.response;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import com.ace.common.util.MaskingUtil;
import com.ace.coupon.entity.CouponIssue;
import com.ace.coupon.enums.CouponIssueStatus;

public record CouponIssuanceLogItemResponse(
		Long userId,
		String maskedUserName,
		String maskedUserEmail,
		String maskedUserPhone,
		Integer issueSequence,
		CouponIssueStatus status,
		OffsetDateTime issuedAt,
		OffsetDateTime confirmedAt) {

	public static CouponIssuanceLogItemResponse from(CouponIssue issue, ZoneId zoneId) {
		return new CouponIssuanceLogItemResponse(
				issue.getUser().getId(),
				MaskingUtil.maskName(issue.getUser().getName()),
				MaskingUtil.maskEmail(issue.getUser().getEmail()),
				MaskingUtil.maskPhone(issue.getUser().getPhone()),
				issue.getIssueSequence(),
				issue.getStatus(),
				issue.getIssuedAt().atZone(zoneId).toOffsetDateTime(),
				issue.getCreatedAt().atZone(zoneId).toOffsetDateTime());
	}
}
