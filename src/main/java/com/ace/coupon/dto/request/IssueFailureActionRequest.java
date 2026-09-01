package com.ace.coupon.dto.request;

import jakarta.validation.constraints.Size;

// RESOLVE 는 reason 이 필수
public record IssueFailureActionRequest(
		@Size(max = 60, message = "operator는 60자 이하여야 합니다.")
		String operator,

		@Size(max = 300, message = "reason은 300자 이하여야 합니다.")
		String reason) {
}
