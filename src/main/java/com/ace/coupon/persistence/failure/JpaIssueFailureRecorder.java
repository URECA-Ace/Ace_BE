package com.ace.coupon.persistence.failure;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ace.common.util.MaskingUtil;
import com.ace.coupon.entity.IssueFailureLog;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.repository.IssueFailureLogRepository;

import lombok.extern.slf4j.Slf4j;

// issue_failure_log에 적재
@Slf4j
@Component
public class JpaIssueFailureRecorder implements IssueFailureRecorder {

	// 스택트레이스 전체가 들어오면 로그 테이블이 부풀어 오른다
	private static final int MAX_ERROR_MESSAGE_LENGTH = 2_000;

	private final IssueFailureLogRepository repository;
	private final ZoneId zoneId;

	public JpaIssueFailureRecorder(
			IssueFailureLogRepository repository,
			CouponIssueRedisProperties redisProperties) {
		this.repository = repository;
		this.zoneId = redisProperties.zoneId();
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void record(IssueFailure failure) {
		try {
			repository.save(IssueFailureLog.builder()
					.eventId(failure.eventId())
					.userId(failure.userId())
					.requestId(failure.requestId())
					.issueSequence(failure.issueSequence())
					.failureStage(failure.stage())
					.compensationResult(failure.compensationResult())
					.errorMessage(maskedMessage(failure.errorMessage()))
					.incidentId(failure.incidentId())
					.occurredAt(LocalDateTime.ofInstant(failure.occurredAt(), zoneId))
					.build());
		} catch (RuntimeException exception) {
			// 실패 기록의 실패를 다시 기록 X
			log.error("실패 기록 적재 실패: requestId={}, stage={}, incidentId={}",
					failure.requestId(), failure.stage(), failure.incidentId(), exception);
		}
	}

	// 예외 메시지에 이메일/이름/전화번호가 섞여 들어온다
	private String maskedMessage(String message) {
		if (message == null) {
			return null;
		}
		String masked = MaskingUtil.mask(message);
		return masked.length() <= MAX_ERROR_MESSAGE_LENGTH
				? masked
				: masked.substring(0, MAX_ERROR_MESSAGE_LENGTH);
	}
}
