package com.ace.coupon.service;

import java.util.UUID;

import com.ace.coupon.dto.response.CouponIssueLookupResponse;
import com.ace.coupon.dto.response.CouponStateChangeResponse;


public interface CouponStateService { 
	
	CouponStateChangeResponse use(Long issuedId, Long userId, UUID idempotencyKey, String reason); 
	CouponStateChangeResponse cancel(Long issuedId , Long userId, UUID idempotencyKey, String reason);
	CouponIssueLookupResponse findIssue(Long eventId, Long userId);
}

