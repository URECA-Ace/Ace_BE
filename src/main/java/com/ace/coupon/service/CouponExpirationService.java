package com.ace.coupon.service;

public interface CouponExpirationService {

	int expireDueCoupons(int chunkSize);
}
