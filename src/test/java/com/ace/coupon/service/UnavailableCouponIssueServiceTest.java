package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;

class UnavailableCouponIssueServiceTest {

	private final CouponIssueService couponIssueService = new UnavailableCouponIssueService();

	@Test
	@DisplayName("실제 발급 판정 구현 전에는 성공을 가장하지 않고 503 오류를 반환한다")
	void issueIsTemporarilyUnavailable() {
		CouponException exception = assertThrows(
				CouponException.class,
				() -> couponIssueService.issue(1L, 1L, UUID.randomUUID()));

		assertThat(exception.getErrorCode())
				.isEqualTo(ErrorCode.ISSUE_TEMPORARILY_UNAVAILABLE);
	}
}
