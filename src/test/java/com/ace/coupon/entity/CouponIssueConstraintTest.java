package com.ace.coupon.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.persistence.Table;

class CouponIssueConstraintTest {

	@Test
	@DisplayName("쿠폰 발급 테이블은 중복 발급과 멱등성을 UNIQUE 제약으로 방어한다")
	void couponIssueHasRequiredUniqueConstraints() {
		Table table = CouponIssue.class.getAnnotation(Table.class);
		Set<String> constraintNames = Arrays.stream(table.uniqueConstraints())
				.map(constraint -> constraint.name())
				.collect(Collectors.toSet());

		assertThat(constraintNames).contains(
				"uk_coupon_issue_event_user",
				"uk_coupon_issue_request_id",
				"uk_coupon_issue_message_id");
	}
}
