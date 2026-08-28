package com.ace.coupon.service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.common.util.MaskingUtil;
import com.ace.coupon.dto.response.CouponIssueAcceptedResponse;
import com.ace.coupon.dto.response.CouponIssueStatusResponse;
import com.ace.coupon.persistence.CouponIssuePersistenceProperties;
import com.ace.coupon.persistence.failure.IssueFailureStage;
import com.ace.coupon.persistence.IssuePersistenceCoordinator;
import com.ace.coupon.persistence.IssueRecord;
import com.ace.coupon.redis.CouponIssueDecision;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.redis.CouponIssueRequestState;
import com.ace.coupon.redis.RedisCouponIssueProcessor;
import com.ace.coupon.repository.CouponEventRepository;
import com.ace.user.entity.User;
import com.ace.user.repository.UserRepository;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponIssueServiceImpl implements CouponIssueService {

	private final RedisCouponIssueProcessor issueProcessor;
	private final CouponIssueRedisProperties properties;
	private final CouponEventRepository couponEventRepository;
	private final CouponIssuePersistenceProperties persistenceProperties;
	private final IssuePersistenceCoordinator persistenceCoordinator;
	private final UserRepository userRepository;
	private final MeterRegistry meterRegistry;

	// Grafana 범례에 ErrorCode 이름 대신 한글로 표시하기 위한 라벨. Ace_FE의 발급 판정 실패 사유 토글과 문구를 맞춘다.
	private static final Map<String, String> ISSUE_REASON_LABELS = Map.of(
			"SOLD_OUT", "재고 소진",
			"ALREADY_ISSUED", "중복 발급",
			"EVENT_NOT_OPEN", "오픈 전",
			"EVENT_CLOSED", "마감",
			"IDEMPOTENCY_CONFLICT", "키 충돌",
			"ISSUE_PERSIST_FAILED", "저장 오류",
			"ISSUE_TEMPORARILY_UNAVAILABLE", "일시 불가",
			"EVENT_NOT_FOUND", "캠페인 없음"
	);

	@Override
	public CouponIssueAcceptedResponse issue(Long eventId, Long userId, UUID idempotencyKey) {
		try {
			CouponIssueAcceptedResponse response = doIssue(eventId, userId, idempotencyKey);
			meterRegistry.counter("coupon.issue",
					"result", "success",
					"result_label", "성공").increment();
			return response;
		} catch (CouponException exception) {
			String reason = exception.getErrorCode().name();
			meterRegistry.counter("coupon.issue",
					"result", "fail",
					"result_label", "실패",
					"reason", reason,
					"reason_label", ISSUE_REASON_LABELS.getOrDefault(reason, reason)).increment();
			throw exception;
		}
	}

	private CouponIssueAcceptedResponse doIssue(Long eventId, Long userId, UUID idempotencyKey) {
		CouponIssueDecision decision;
		try {
			decision = issueProcessor.issue(eventId, userId, idempotencyKey);
		} catch (DataAccessException | IllegalStateException exception) {
			throw new CouponException(
					ErrorCode.ISSUE_TEMPORARILY_UNAVAILABLE,
					ErrorCode.ISSUE_TEMPORARILY_UNAVAILABLE.getDefaultMessage(),
					exception);
		}

		return switch (decision.code()) {
			case ACCEPTED -> {
				// RELAY 는 Stream 소비자가 저장한다. 여기서 저장하면 같은 건이 두 번 처리된다
				if (!persistenceProperties.relay()) {
					persist(eventId, userId, idempotencyKey, decision);
				}
				yield CouponIssueAcceptedResponse.accepted(
						idempotencyKey,
						eventId,
						userId,
						decision.issueSequence(),
						decision.remainingStock(),
						OffsetDateTime.ofInstant(decision.decidedAt(), properties.zoneId()));
			}
			case SOLD_OUT -> throw new CouponException(ErrorCode.SOLD_OUT);
			case ALREADY_ISSUED -> throw new CouponException(ErrorCode.ALREADY_ISSUED);
			case EVENT_NOT_OPEN -> throw new CouponException(ErrorCode.EVENT_NOT_OPEN);
			case EVENT_CLOSED -> throw new CouponException(ErrorCode.EVENT_CLOSED);
			case IDEMPOTENCY_CONFLICT -> throw new CouponException(ErrorCode.IDEMPOTENCY_CONFLICT);
			case PERSISTENCE_FAILED -> throw new CouponException(ErrorCode.ISSUE_PERSIST_FAILED);
			case CAMPAIGN_NOT_INITIALIZED -> throw missingCampaignOrUnavailable(eventId);
			case CORRUPTED_STATE, INVALID_ARGUMENT, INTERNAL_WRITE_ERROR ->
					throw new CouponException(ErrorCode.ISSUE_TEMPORARILY_UNAVAILABLE);
		};
	}

	private CouponException missingCampaignOrUnavailable(Long eventId) {
		try {
			if (!couponEventRepository.existsById(eventId)) {
				return new CouponException(ErrorCode.EVENT_NOT_FOUND);
			}
		} catch (DataAccessException exception) {
			return new CouponException(
					ErrorCode.ISSUE_TEMPORARILY_UNAVAILABLE,
					ErrorCode.ISSUE_TEMPORARILY_UNAVAILABLE.getDefaultMessage(),
					exception);
		}
		return new CouponException(ErrorCode.ISSUE_TEMPORARILY_UNAVAILABLE);
	}

	/**
	 * SYNC 경로 — 응답 전에 MySQL 저장까지 끝낸다.
	 *
	 * <p>실패하면 이미 차감된 Redis 재고를 되돌리고 실패를 기록한 뒤 500 으로 응답한다.
	 * 여기서 202 를 돌려주면 사용자는 발급됐다고 알지만 이력이 없다.
	 *
	 * <p>저장 성공 후 요청 상태를 확정으로 올리는 처리(P3)는 아직 없다. 그래서 저장이 끝나도
	 * 상태 조회는 {@code ACCEPTED} 로 보인다. 데이터 정합성에는 영향이 없다 — MySQL 이 확정 소스다.
	 */
	private void persist(Long eventId, Long userId, UUID requestId, CouponIssueDecision decision) {
		// 실패 기록과 응답이 같은 값을 갖도록 여기서 발급한다
		String incidentId = UUID.randomUUID().toString();
		IssueRecord record = IssueRecord.fromDecision(eventId, userId, requestId, decision);

		try {
			persistenceCoordinator.persist(record, IssueFailureStage.DB_INSERT, incidentId);
		} catch (RuntimeException exception) {
			throw new CouponException(
					ErrorCode.ISSUE_PERSIST_FAILED,
					ErrorCode.ISSUE_PERSIST_FAILED.getDefaultMessage(),
					exception,
					incidentId);
		}
	}

	@Override
	public CouponIssueStatusResponse findStatus(Long eventId, UUID requestId) {
		CouponIssueRequestState state;
		try {
			state = issueProcessor.findRequest(eventId, requestId);
		} catch (DataAccessException | IllegalStateException exception) {
			throw new CouponException(
					ErrorCode.ISSUE_TEMPORARILY_UNAVAILABLE,
					ErrorCode.ISSUE_TEMPORARILY_UNAVAILABLE.getDefaultMessage(),
					exception);
		}

		if (state == null) {
			throw new CouponException(ErrorCode.ISSUE_NOT_FOUND);
		}
		User user = findUserForMonitoring(state.userId());

		return new CouponIssueStatusResponse(
				state.requestId(),
				state.campaignId(),
				state.userId(),
				state.issueSequence(),
				state.remainingStock(),
				state.status(),
				OffsetDateTime.ofInstant(state.decidedAt(), properties.zoneId()),
				user == null ? null : MaskingUtil.maskName(user.getName()),
				user == null ? null : MaskingUtil.maskEmail(user.getEmail()),
				user == null ? null : MaskingUtil.maskPhone(user.getPhone()));
	}

	private User findUserForMonitoring(long userId) {
		try {
			return userRepository.findById(userId).orElse(null);
		} catch (DataAccessException exception) {
			// 사용자 정보 부가 조회 장애가 Redis 기반 발급 상태 조회까지 막지 않게 한다.
			log.warn("발급 상태 사용자 정보 조회 실패: userId={}", userId, exception);
			return null;
		}
	}
}
