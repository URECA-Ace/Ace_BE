package com.ace.coupon.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ace.coupon.enums.CouponIssueStatus;

class CouponIssueRevokeTest {

	@Test
	@DisplayName("ISSUED 상태의 발급 건은 재고 회수 시 CANCELED로 전이하고 canceled_at을 기록한다")
	void revokesIssuedIssue() {
		CouponIssue issue = CouponIssue.builder()
				.status(CouponIssueStatus.ISSUED)
				.build();
		LocalDateTime revokedAt = LocalDateTime.of(2026, 8, 26, 10, 0);

		issue.revoke(revokedAt);

		assertThat(issue.getStatus()).isEqualTo(CouponIssueStatus.CANCELED);
		assertThat(issue.getCanceledAt()).isEqualTo(revokedAt);
	}

	@Test
	@DisplayName("ISSUED가 아닌 발급 건은 일반 상태머신과 무관하게 회수를 거부한다")
	void rejectsRevokeForNonIssuedStatus() {
		CouponIssue issue = CouponIssue.builder()
				.status(CouponIssueStatus.USED)
				.build();

		assertThatThrownBy(() -> issue.revoke(LocalDateTime.now()))
				.isInstanceOf(IllegalStateException.class);
	}
}
