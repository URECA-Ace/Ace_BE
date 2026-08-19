package com.ace.coupon.persistence.relay;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.redis.CouponRedisKeys;
import com.ace.coupon.repository.CouponEventRepository;

import lombok.RequiredArgsConstructor;

// Stream 소비 대상 캠페인 목록
@Component
@RequiredArgsConstructor
public class RelayTargetProvider {

	private final CouponEventRepository couponEventRepository;
	private final StringRedisTemplate redisTemplate;
	private final CouponIssueRedisProperties redisProperties;

	@Transactional(readOnly = true)
	public List<Long> campaignIds() {
		LocalDateTime now = LocalDateTime.now(redisProperties.zoneId());
		LocalDateTime since = now.minus(redisProperties.retention());

		return couponEventRepository.findConsumableEventIds(now, since).stream()
				.filter(this::initialized)
				.toList();
	}

	// 초기화되지 않은 회차까지 그룹을 만들면 시작도 안 한 캠페인에 빈 Stream 이 생긴다
	private boolean initialized(Long campaignId) {
		return Boolean.TRUE.equals(
				redisTemplate.hasKey(CouponRedisKeys.campaign(campaignId).issueStream()));
	}
}
