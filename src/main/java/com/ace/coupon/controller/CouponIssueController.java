package com.ace.coupon.controller;


import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ace.common.ApiResponse;
import com.ace.coupon.service.CouponIssueService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/coupons")
@RequiredArgsConstructor
public class CouponIssueController {

	private final CouponIssueService couponIssueService;
	
	@PostMapping("/{couponId}/issue")
	public ResponseEntity<ApiResponse<String>> issueCoupon(
			
			@PathVariable("couponId") Long couponId,
			@RequestParam("userId") Long userId){
		
		String message = couponIssueService.issueCoupon(couponId,  userId);
		
		
		return ResponseEntity.ok(
				ApiResponse.success(message
		));
	}
}
