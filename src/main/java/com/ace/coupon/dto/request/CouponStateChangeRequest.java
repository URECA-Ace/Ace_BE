package com.ace.coupon.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CouponStateChangeRequest (
		
	@NotNull(message = "userId는 필수입니다")
	@Positive(message = "usdrId는 0보다 커야합니다") 
	
	Long userId,
	String reason 
)
{}
