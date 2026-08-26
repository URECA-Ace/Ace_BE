package com.ace.coupon.dto.request;

import com.ace.coupon.enums.CouponType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CouponCreateRequest(
		@NotBlank(message = "couponName은 필수입니다.")
		@Size(max = 100, message = "couponName은 100자 이하여야 합니다.")
		String couponName,

		@NotNull(message = "type은 필수입니다.")
		CouponType type,

		@NotNull(message = "value는 필수입니다.")
		@PositiveOrZero(message = "value는 0 이상이어야 합니다.")
		Long value,

		@NotNull(message = "validHours는 필수입니다.")
		@Positive(message = "validHours는 0보다 커야 합니다.")
		Integer validHours) {
}
