package com.ace.coupon.dto.request;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CouponEventCreateRequest(
		@NotNull(message = "round는 필수입니다.")
		@Positive(message = "round는 0보다 커야 합니다.")
		Integer round,

		@NotNull(message = "totalStock은 필수입니다.")
		@Positive(message = "totalStock은 0보다 커야 합니다.")
		Integer totalStock,

		@NotNull(message = "openAt은 필수입니다.")
		OffsetDateTime openAt,

		@NotNull(message = "closeAt은 필수입니다.")
		OffsetDateTime closeAt) {
}
