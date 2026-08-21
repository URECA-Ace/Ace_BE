package com.ace.coupon.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.repository.CampaignRedisInitializationRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CampaignRedisInitializationStateService {

	private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

	private final CampaignRedisInitializationRepository repository;
	private final CouponIssueRedisProperties properties;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordAttempt(Long eventId) {
		LocalDateTime now = now();
		if (repository.recordAttempt(eventId, now) == 0) {
			throw new IllegalStateException("Redis 초기화 시도를 기록할 수 없습니다: " + eventId);
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordSuccess(Long eventId) {
		LocalDateTime now = now();
		if (repository.recordSuccess(eventId, now) != 1) {
			throw new IllegalStateException("Redis 초기화 성공을 기록할 수 없습니다: " + eventId);
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordFailure(Long eventId, String errorCode, String errorMessage) {
		LocalDateTime now = now();
		// 다른 인스턴스가 이미 성공을 기록했다면 0건 갱신이 정상이다.
		// 늦게 도착한 실패가 INITIALIZED를 FAILED로 되돌려서는 안 된다.
		repository.recordFailure(eventId, errorCode, truncate(errorMessage), now);
	}

	private LocalDateTime now() {
		return LocalDateTime.now(properties.zoneId());
	}

	private String truncate(String message) {
		if (message == null || message.length() <= MAX_ERROR_MESSAGE_LENGTH) {
			return message;
		}
		return message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
	}
}
