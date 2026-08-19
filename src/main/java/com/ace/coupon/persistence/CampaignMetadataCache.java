package com.ace.coupon.persistence;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.repository.CouponEventRepository;


import lombok.RequiredArgsConstructor;

// 회차 불변값 캐시
// coupon_issue.valid_from/valid_to 는 NOT NULL 인데 Stream 엔트리에 없어 회차에서 계산
// 저장할 때마다 조회하면 이미 병목인 커넥션 풀을 조회가 한 번 더 점유하기 때문에 구현
@Component
@RequiredArgsConstructor
public class CampaignMetadataCache {

	private final CouponEventRepository couponEventRepository;
	private final CouponIssuePersistenceProperties properties;

	private final ConcurrentMap<Long, CampaignMetadata> cache = new ConcurrentHashMap<>();

	public CampaignMetadata get(long eventId) {
		// 캐시를 끄면 저장 건마다 회차를 다시 조회(측정용, 문서화 후 제거 예정)
		if (!properties.campaignCache()) {
			return load(eventId);
		}
		return cache.computeIfAbsent(eventId, this::load);
	}

	private CampaignMetadata load(Long eventId) {
		// 판정이 통과했는데 회차가 없다면 Redis 와 MySQL 이 어긋난 것
		CouponEvent event = couponEventRepository.findWithCouponById(eventId)
				.orElseThrow(() -> new IllegalStateException("회차를 찾을 수 없습니다: " + eventId));

		return new CampaignMetadata(
				event.getId(),
				event.getCoupon().getValidHours(),
				event.getOpenAt(),
				event.getCloseAt());
	}
}
