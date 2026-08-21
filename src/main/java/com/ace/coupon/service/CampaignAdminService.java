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
import com.ace.coupon.persistence.relay.RelayTargetProvider;
import com.ace.coupon.repository.CouponEventRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
	private final CampaignRedisInitializationStateService initializationStateService;
	private final CouponIssueRedisProperties properties;

	@Transactional(readOnly = true)
	public CampaignInitializationResponse initialize(Long eventId) {
		CouponEvent event = couponEventRepository.findById(eventId)
				.orElseThrow(() -> new CouponException(ErrorCode.EVENT_NOT_FOUND));
		return initialize(event);
	}

	/**
	 * DB에서 저장 또는 조회한 회차 값으로 Redis를 초기화한다.
	 * 생성 API와 복구 스케줄러가 내부 운영 API와 동일한 결과 해석을 사용한다.
	 */
	public CampaignInitializationResponse initialize(CouponEvent event) {
		if (event == null || event.getId() == null) {
			throw new IllegalArgumentException("영속화된 캠페인이 필요합니다.");
		}

		initializationStateService.recordAttempt(event.getId());

		CampaignInitializationResult result;
		try {
			result = campaignRedisInitializer.initialize(event);
		} catch (RuntimeException exception) {
			recordFailureSafely(event.getId(), "REDIS_CALL_FAILED", exception.getMessage(), exception);
			throw exception;
		}
		log.info("캠페인 Redis 초기화: eventId={}, result={}, totalStock={}",
				event.getId(), result, event.getTotalStock());

		if (result == CampaignInitializationResult.INITIALIZED
				|| result == CampaignInitializationResult.ALREADY_INITIALIZED) {
			initializationStateService.recordSuccess(event.getId());
			return response(event, result);
		}

		CouponException failure = initializationFailure(event, result);
		recordFailureSafely(event.getId(), result.name(), failure.getMessage(), failure);
		throw failure;
	}

	private CouponException initializationFailure(
			CouponEvent event,
			CampaignInitializationResult result) {
		return switch (result) {
			// 재실행해도 같은 설정이면 성공으로 본다. 부하테스트 스크립트가 매번 확인하고 넘어갈 수 있어야 한다
			case INITIALIZED, ALREADY_INITIALIZED -> throw new IllegalArgumentException(
					"성공한 초기화 결과는 실패로 변환할 수 없습니다: " + result);
			case CONFIGURATION_CONFLICT -> new CouponException(
					ErrorCode.CAMPAIGN_CONFIG_CONFLICT,
					"회차 %d 가 이미 다른 설정으로 초기화되어 있습니다. 키를 지우고 다시 실행하세요."
							.formatted(event.getId()));
			// 서버 잘못이 아니라 입력 잘못
			// 이미 마감된 회차를 올리려는 경우가 대부분이라 사유를 알려준다
			case INVALID_CONFIGURATION -> new CouponException(
					ErrorCode.CAMPAIGN_NOT_INITIALIZABLE,
					("회차 %d 를 초기화할 수 없습니다. 보존기간이 지났거나 설정이 올바르지 않습니다. "
							+ "openAt=%s, closeAt=%s, totalStock=%d")
							.formatted(event.getId(), event.getOpenAt(), event.getCloseAt(),
									event.getTotalStock()));
			case INTERNAL_WRITE_ERROR -> new CouponException(
					ErrorCode.CAMPAIGN_INIT_FAILED,
					ErrorCode.CAMPAIGN_INIT_FAILED.getDefaultMessage());
		};
	}

	private void recordFailureSafely(
			Long eventId,
			String errorCode,
			String errorMessage,
			RuntimeException original) {
		try {
			initializationStateService.recordFailure(eventId, errorCode, errorMessage);
		} catch (RuntimeException recordingFailure) {
			original.addSuppressed(recordingFailure);
			log.error("캠페인 Redis 초기화 실패 상태 기록 실패: eventId={}, errorCode={}",
					eventId, errorCode, recordingFailure);
		}
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
