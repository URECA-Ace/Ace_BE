package com.ace.coupon.service;

import org.springframework.stereotype.Service;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.repository.CouponRedisRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponIssueService {
	
	private static final Long sucess = 0L;
	private static final Long out_of_stock = 1L;
	private static final Long already_issued = 2L;
	
	private final CouponRedisRepository couponRedisRepository;
	
	public String issueCoupon(Long couponId, Long userId) {
		Long result = couponRedisRepository.issue(couponId, userId);
		if(out_of_stock.equals(result)) {
			throw new CouponException(ErrorCode.SOLD_OUT);			//	< == 재고 소진 
			
		}
		if (already_issued.equals(result)) {
			throw new CouponException(ErrorCode.ALREADY_ISSUED);		//< == 이미 발급받은 쿠폰의 경우 
		}
		
		return  "쿠폰 발급에 성공했습니다.";
	}
}
