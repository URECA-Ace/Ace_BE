package com.ace.coupon.service;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.response.CampaignInitializationResponse;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.redis.CampaignInitializationResult;
import com.ace.coupon.redis.CampaignRedisInitializer;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.repository.CouponEventRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.ace.coupon.persistence.relay.RelayTargetProvider;

/**
 * 캠페인 Redis 초기화.
 *
 * <p><b>MySQL 회차 값을 그대로 Redis 에 넣는다.</b> 손으로 {@code redis-cli --eval} 을 치면
 * 재고·오픈·마감이 회차 행과 어긋날 수 있는데, 그러면 발급은 되는데 Stream 소비자가 그 회차를
 * 조용히 건너뛰는 식으로 드러난다({@code RelayTargetProvider} 가 MySQL 기간으로 대상을 고른다).
 * 한쪽에서만 값을 읽어 양쪽 드리프트를 없앤다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignAdminService {

	private final CouponEventRepository couponEventRepository;
	private final CampaignRedisInitializer campaignRedisInitializer;
	private final CouponIssueRedisProperties properties;

	@Transactional(readOnly = true)
	public CampaignInitializationResponse initialize(Long eventId) {
		CouponEvent event = couponEventRepository.findById(eventId)
				.orElseThrow(() -> new CouponException(ErrorCode.EVENT_NOT_FOUND));

		CampaignInitializationResult result = campaignRedisInitializer.initialize(event);
		log.info("캠페인 Redis 초기화: eventId={}, result={}, totalStock={}",
				eventId, result, event.getTotalStock());

		return switch (result) {
			// 재실행해도 같은 설정이면 성공으로 본다. 부하테스트 스크립트가 매번 확인하고 넘어갈 수 있어야 한다
			case INITIALIZED, ALREADY_INITIALIZED -> response(event, result);
			case CONFIGURATION_CONFLICT -> throw new CouponException(
					ErrorCode.CAMPAIGN_CONFIG_CONFLICT,
					"회차 %d 가 이미 다른 설정으로 초기화되어 있습니다. 키를 지우고 다시 실행하세요."
							.formatted(eventId));
			case INVALID_CONFIGURATION -> throw new CouponException(
					ErrorCode.CAMPAIGN_INIT_FAILED,
					"회차 %d 의 설정이 올바르지 않습니다: totalStock=%d, openAt=%s, closeAt=%s"
							.formatted(eventId, event.getTotalStock(),
									event.getOpenAt(), event.getCloseAt()));
			case INTERNAL_WRITE_ERROR -> throw new CouponException(
					ErrorCode.CAMPAIGN_INIT_FAILED,
					ErrorCode.CAMPAIGN_INIT_FAILED.getDefaultMessage());
		};
	}

	private CampaignInitializationResponse response(
			CouponEvent event,
			CampaignInitializationResult result) {
		return new CampaignInitializationResponse(
				event.getId(),
				result,
				event.getTotalStock(),
				OffsetDateTime.of(event.getOpenAt(), zoneOffset(event)),
				OffsetDateTime.of(event.getCloseAt(), zoneOffset(event)));
	}

	private java.time.ZoneOffset zoneOffset(CouponEvent event) {
		return properties.zoneId().getRules().getOffset(event.getOpenAt());
	}
}
