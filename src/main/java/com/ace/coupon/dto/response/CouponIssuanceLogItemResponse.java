package com.ace.coupon.dto.response;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import com.ace.common.util.MaskingUtil;
import com.ace.coupon.entity.CouponIssue;
import com.ace.coupon.persistence.IssueRecord;
import com.ace.user.entity.User;

public record CouponIssuanceLogItemResponse(
		Long userId,
		String maskedUserName,
		String maskedUserEmail,
		String maskedUserPhone,
		Long issueSequence,
		String status,
		OffsetDateTime issuedAt,
		OffsetDateTime persistedAt) {

	public static CouponIssuanceLogItemResponse from(CouponIssue issue, ZoneId zoneId) {
		return new CouponIssuanceLogItemResponse(
				issue.getUser().getId(),
				MaskingUtil.maskName(issue.getUser().getName()),
				MaskingUtil.maskEmail(issue.getUser().getEmail()),
				MaskingUtil.maskPhone(issue.getUser().getPhone()),
				issue.getIssueSequence().longValue(),
				issue.getStatus().name(),
				issue.getIssuedAt().atZone(zoneId).toOffsetDateTime(),
				issue.getCreatedAt().atZone(zoneId).toOffsetDateTime());
	}

	public static CouponIssuanceLogItemResponse processing(
			IssueRecord record,
			User user,
			ZoneId zoneId) {
		return new CouponIssuanceLogItemResponse(
				record.userId(),
				user == null ? null : MaskingUtil.maskName(user.getName()),
				user == null ? null : MaskingUtil.maskEmail(user.getEmail()),
				user == null ? null : MaskingUtil.maskPhone(user.getPhone()),
				record.issueSequence(),
				"PROCESSING",
				record.decidedAt().atZone(zoneId).toOffsetDateTime(),
				null);
	}
}
