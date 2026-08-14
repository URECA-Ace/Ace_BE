package com.ace.coupon.repository;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class CouponRedisRepository {
	
	private final StringRedisTemplate  redisTemplate;
	private final RedisScript<Long> issueCouponScript;
	
	public Long issue(Long couponId, Long userId) {
		String stockKey = "coupon:" + couponId +":stock";
		String userKey = "coupon:" + couponId + ":user";
		
		return redisTemplate.execute(
		        issueCouponScript,
		        List.of(stockKey, userKey), 
		        String.valueOf(userId));
	}
	
	//이벤트 발생 전 레디스 재고 세팅
	public void initStock(Long couponId, int totalStock) {
		
		String stockKey = "coupon:"+couponId + ":stock";
		redisTemplate.opsForValue().set(stockKey, String.valueOf(totalStock));
	}
	
	//비동기ㅏ 세이브 못할때 호출되는 보상 트랜잭션
	// 재고를 1 증가시키고 유저를 발급목록에서 제거시키기
	// 추후 kafka사용시 이런 로직으로 구현하는걸 고려중 
	
}
