package com.ace.coupon.service;

import com.ace.coupon.dto.response.CouponEventStatsResponse;

public interface CouponEventStatsService {

	CouponEventStatsResponse findStats(Long eventId);
}
