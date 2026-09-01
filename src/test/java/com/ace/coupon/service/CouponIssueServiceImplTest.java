package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.response.CouponIssueAcceptedResponse;
import com.ace.coupon.dto.response.CouponIssueStatusResponse;
import com.ace.coupon.enums.IssueRequestStatus;
import com.ace.coupon.persistence.CouponIssuePersistenceProperties;
import com.ace.coupon.persistence.failure.IssueFailureStage;
import com.ace.coupon.persistence.IssuePersistenceCoordinator;
import com.ace.coupon.persistence.IssueRecord;
import com.ace.coupon.persistence.PersistenceMode;
import com.ace.coupon.redis.CouponIssueDecision;
import com.ace.coupon.redis.CouponIssueLuaCode;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.redis.CouponIssueRequestState;
import com.ace.coupon.redis.RedisCouponIssueProcessor;
import com.ace.coupon.repository.CouponEventRepository;
import com.ace.event.coupon.CouponIssueFailedEvent;
import com.ace.user.entity.User;
import com.ace.user.repository.UserRepository;

class CouponIssueServiceImplTest {

	private RedisCouponIssueProcessor processor;
	private CouponEventRepository couponEventRepository;
	private IssuePersistenceCoordinator coordinator;
	private UserRepository userRepository;
	private CouponIssueFailureAggregator failureAggregator;
	private CouponIssueService service;

	@BeforeEach
	void setUp() {
		service = serviceWithMode(PersistenceMode.SYNC);
	}

	private CouponIssueService serviceWithMode(PersistenceMode mode) {
		processor = Mockito.mock(RedisCouponIssueProcessor.class);
		couponEventRepository = Mockito.mock(CouponEventRepository.class);
		coordinator = Mockito.mock(IssuePersistenceCoordinator.class);
		userRepository = Mockito.mock(UserRepository.class);
		failureAggregator = Mockito.mock(CouponIssueFailureAggregator.class);
		return new CouponIssueServiceImpl(
				processor,
				new CouponIssueRedisProperties(Duration.ofDays(7), ZoneId.of("Asia/Seoul")),
				couponEventRepository,
				new CouponIssuePersistenceProperties(mode, null, null, null, null, null, null),
				coordinator,
				userRepository,
				new SimpleMeterRegistry(),
				failureAggregator);
	}

	@Test
	@DisplayName("Lua 승인 결과를 202 응답 데이터로 변환한다")
	void returnsAcceptedResponse() {
		UUID requestId = UUID.randomUUID();
		Instant decidedAt = Instant.parse("2026-08-14T06:00:00Z");
		given(processor.issue(1L, 2L, requestId))
				.willReturn(new CouponIssueDecision(CouponIssueLuaCode.ACCEPTED, 7L, 93L, decidedAt));

		CouponIssueAcceptedResponse response = service.issue(1L, 2L, requestId);

		assertThat(response.requestId()).isEqualTo(requestId);
		assertThat(response.issueSequence()).isEqualTo(7L);
		assertThat(response.remainingStock()).isEqualTo(93L);
		assertThat(response.status()).isEqualTo(IssueRequestStatus.ACCEPTED);
		assertThat(response.acceptedAt().toInstant()).isEqualTo(decidedAt);
	}

	@Test
	@DisplayName("Lua 재고 소진 결과를 명확한 도메인 오류로 변환한다")
	void mapsSoldOut() {
		UUID requestId = UUID.randomUUID();
		given(processor.issue(1L, 2L, requestId))
				.willReturn(new CouponIssueDecision(
						CouponIssueLuaCode.SOLD_OUT, null, 0L, Instant.now()));

		assertThatThrownBy(() -> service.issue(1L, 2L, requestId))
				.isInstanceOfSatisfying(CouponException.class,
						exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SOLD_OUT));
	}

	@Test
	@DisplayName("발급 실패는 건마다 알림을 쏘지 않고 실패 집계기에만 기록한다")
	void recordsFailureInAggregatorInsteadOfPublishingPerRequest() {
		UUID requestId = UUID.randomUUID();
		given(processor.issue(1L, 2L, requestId))
				.willReturn(new CouponIssueDecision(CouponIssueLuaCode.SOLD_OUT, null, 0L, Instant.now()));

		assertThatThrownBy(() -> service.issue(1L, 2L, requestId)).isInstanceOf(CouponException.class);

		verify(failureAggregator).record(1L, CouponIssueFailedEvent.FailReason.SOLD_OUT);
	}

	@Test
	@DisplayName("Redis와 DB에 모두 없는 캠페인은 404로 구분한다")
	void mapsMissingCampaignToNotFound() {
		UUID requestId = UUID.randomUUID();
		given(processor.issue(99L, 2L, requestId))
				.willReturn(new CouponIssueDecision(
						CouponIssueLuaCode.CAMPAIGN_NOT_INITIALIZED, null, null, null));
		given(couponEventRepository.existsById(99L)).willReturn(false);

		assertThatThrownBy(() -> service.issue(99L, 2L, requestId))
				.isInstanceOfSatisfying(CouponException.class,
						exception -> assertThat(exception.getErrorCode())
								.isEqualTo(ErrorCode.EVENT_NOT_FOUND));
	}

	@Test
	@DisplayName("DB에는 있지만 Redis 초기화 전인 캠페인은 503으로 구분한다")
	void mapsUninitializedCampaignToUnavailable() {
		UUID requestId = UUID.randomUUID();
		given(processor.issue(1L, 2L, requestId))
				.willReturn(new CouponIssueDecision(
						CouponIssueLuaCode.CAMPAIGN_NOT_INITIALIZED, null, null, null));
		given(couponEventRepository.existsById(1L)).willReturn(true);

		assertThatThrownBy(() -> service.issue(1L, 2L, requestId))
				.isInstanceOfSatisfying(CouponException.class,
						exception -> assertThat(exception.getErrorCode())
								.isEqualTo(ErrorCode.ISSUE_TEMPORARILY_UNAVAILABLE));
	}

	@Test
	@DisplayName("requestId 상태를 Redis 판정 상태로 조회한다")
	void findsRequestStatus() {
		UUID requestId = UUID.randomUUID();
		Instant decidedAt = Instant.parse("2026-08-14T06:00:00Z");
		given(processor.findRequest(1L, requestId)).willReturn(new CouponIssueRequestState(
				requestId, 1L, 2L, IssueRequestStatus.ACCEPTED, 7L, 93L, decidedAt));
		given(userRepository.findById(2L)).willReturn(java.util.Optional.of(
				User.builder()
						.id(2L)
						.name("홍길동")
						.email("honggildong@example.com")
						.phone("010-1234-5678")
						.build()));

		CouponIssueStatusResponse response = service.findStatus(1L, requestId);

		assertThat(response.status()).isEqualTo(IssueRequestStatus.ACCEPTED);
		assertThat(response.decidedAt().toInstant()).isEqualTo(decidedAt);
		assertThat(response.maskedUserName()).isEqualTo("홍*동");
		assertThat(response.maskedUserEmail()).isEqualTo("hon****@example.com");
		assertThat(response.maskedUserPhone()).isEqualTo("010-****-5678");
	}

	@Test
	@DisplayName("존재하지 않는 requestId는 조회 오류로 변환한다")
	void rejectsMissingRequestStatus() {
		UUID requestId = UUID.randomUUID();
		given(processor.findRequest(1L, requestId)).willReturn(null);

		assertThatThrownBy(() -> service.findStatus(1L, requestId))
				.isInstanceOfSatisfying(CouponException.class,
						exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ISSUE_NOT_FOUND));
	}

	@Test
	@DisplayName("SYNC 모드는 응답 전에 저장까지 끝낸다")
	void persistsBeforeRespondingInSyncMode() {
		UUID requestId = UUID.randomUUID();
		Instant decidedAt = Instant.parse("2026-08-19T03:00:00Z");
		given(processor.issue(1L, 2L, requestId))
				.willReturn(new CouponIssueDecision(CouponIssueLuaCode.ACCEPTED, 7L, 93L, decidedAt));

		service.issue(1L, 2L, requestId);

		ArgumentCaptor<IssueRecord> captor = ArgumentCaptor.forClass(IssueRecord.class);
		verify(coordinator).persist(captor.capture(), eq(IssueFailureStage.DB_INSERT), anyString());
		IssueRecord record = captor.getValue();
		assertThat(record.requestId()).isEqualTo(requestId);
		assertThat(record.issueSequence()).isEqualTo(7L);
		assertThat(record.decidedAt()).isEqualTo(decidedAt);
		// Stream 을 거치지 않았으므로 message_id 가 없다
		assertThat(record.messageId()).isNull();
	}

	@Test
	@DisplayName("RELAY 모드는 저장하지 않는다")
	void doesNotPersistInRelayMode() {
		CouponIssueService relayService = serviceWithMode(PersistenceMode.RELAY);
		UUID requestId = UUID.randomUUID();
		given(processor.issue(1L, 2L, requestId)).willReturn(
				new CouponIssueDecision(CouponIssueLuaCode.ACCEPTED, 7L, 93L, Instant.now()));

		CouponIssueAcceptedResponse response = relayService.issue(1L, 2L, requestId);

		assertThat(response.issueSequence()).isEqualTo(7L);
		verify(coordinator, never()).persist(any(), any(), anyString());
	}

	@Test
	@DisplayName("거절 판정은 저장하지 않는다")
	void doesNotPersistRejectedDecision() {
		UUID requestId = UUID.randomUUID();
		given(processor.issue(1L, 2L, requestId)).willReturn(
				new CouponIssueDecision(CouponIssueLuaCode.SOLD_OUT, null, 0L, Instant.now()));

		assertThatThrownBy(() -> service.issue(1L, 2L, requestId))
				.isInstanceOf(CouponException.class);

		verify(coordinator, never()).persist(any(), any(), anyString());
	}

	@Test
	@DisplayName("저장이 실패하면 202가 아니라 500으로 응답한다")
	void failsWhenPersistenceFails() {
		UUID requestId = UUID.randomUUID();
		given(processor.issue(1L, 2L, requestId)).willReturn(
				new CouponIssueDecision(CouponIssueLuaCode.ACCEPTED, 7L, 93L, Instant.now()));
		given(coordinator.persist(any(), any(), anyString()))
				.willThrow(new IllegalStateException("저장 실패"));

		assertThatThrownBy(() -> service.issue(1L, 2L, requestId))
				.isInstanceOfSatisfying(CouponException.class, exception -> {
					assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ISSUE_PERSIST_FAILED);
					assertThat(exception.getIncidentId()).isNotBlank();
				});
	}

	@Test
	@DisplayName("응답의 incidentId와 실패 기록에 넘긴 값이 같다")
	void reusesIncidentIdFromPersistence() {
		UUID requestId = UUID.randomUUID();
		given(processor.issue(1L, 2L, requestId)).willReturn(
				new CouponIssueDecision(CouponIssueLuaCode.ACCEPTED, 7L, 93L, Instant.now()));
		given(coordinator.persist(any(), any(), anyString()))
				.willThrow(new IllegalStateException("저장 실패"));

		CouponException exception = catchThrowableOfType(
				() -> service.issue(1L, 2L, requestId), CouponException.class);

		ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
		verify(coordinator).persist(any(), any(), captor.capture());
		assertThat(exception.getIncidentId()).isEqualTo(captor.getValue());
	}
}
