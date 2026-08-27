package com.ace.coupon.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.entity.CouponIssue;
import com.ace.coupon.persistence.failure.IssueFailureRecorder;
import com.ace.coupon.persistence.failure.IssueFailureStage;
import com.ace.coupon.redis.CouponIssueCompensationResult;
import com.ace.coupon.redis.RedisCouponIssueProcessor;
import com.ace.coupon.repository.CouponIssueRepository;
import com.ace.user.entity.User;

class IssuePersistenceProbeTest {

	private CouponIssueRepository couponIssueRepository;
	private IssuePersistenceProbe probe;

	@BeforeEach
	void setUp() {
		couponIssueRepository = Mockito.mock(CouponIssueRepository.class);
		probe = new IssuePersistenceProbe(couponIssueRepository);
	}

	@Test
	@DisplayName("requestId와 캠페인, 사용자, 순번이 모두 같을 때만 저장 완료로 판단한다")
	void reportsPersistedOnlyForSameLogicalIssue() {
		IssueRecord record = record(1L, 7L, 3L);
		given(couponIssueRepository.findByRequestId(record.requestId().toString()))
				.willReturn(Optional.of(stored(record.requestId(), 1L, 7L, 3)));

		assertThat(probe.probe(record)).isEqualTo(IssuePersistenceProbe.Result.PERSISTED);
	}

	@Test
	@DisplayName("같은 requestId의 다른 캠페인 행은 현재 요청이 저장된 것으로 보지 않는다")
	void reportsAbsentForDifferentCampaign() {
		IssueRecord record = record(1L, 7L, 3L);
		given(couponIssueRepository.findByRequestId(record.requestId().toString()))
				.willReturn(Optional.of(stored(record.requestId(), 2L, 7L, 3)));

		assertThat(probe.probe(record)).isEqualTo(IssuePersistenceProbe.Result.ABSENT);
	}

	@Test
	@DisplayName("같은 requestId의 다른 사용자 행은 현재 요청이 저장된 것으로 보지 않는다")
	void reportsAbsentForDifferentUser() {
		IssueRecord record = record(1L, 7L, 3L);
		given(couponIssueRepository.findByRequestId(record.requestId().toString()))
				.willReturn(Optional.of(stored(record.requestId(), 1L, 8L, 3)));

		assertThat(probe.probe(record)).isEqualTo(IssuePersistenceProbe.Result.ABSENT);
	}

	@Test
	@DisplayName("같은 requestId의 다른 발급 행이 있어도 현재 요청의 재고와 Bitmap을 보상한다")
	void compensatesCurrentIssueWhenStoredIdentityDiffers() {
		IssueRecord record = record(1L, 7L, 3L);
		given(couponIssueRepository.findByRequestId(record.requestId().toString()))
				.willReturn(Optional.of(stored(record.requestId(), 1L, 8L, 3)));
		IssuePersistenceService persistenceService = Mockito.mock(IssuePersistenceService.class);
		RedisCouponIssueProcessor issueProcessor = Mockito.mock(RedisCouponIssueProcessor.class);
		IssueFailureRecorder failureRecorder = Mockito.mock(IssueFailureRecorder.class);
		IssuePersistenceCoordinator coordinator = new IssuePersistenceCoordinator(
				persistenceService, probe, issueProcessor, failureRecorder);
		given(persistenceService.persist(record)).willThrow(new IllegalStateException("저장 실패"));
		given(issueProcessor.compensate(record.campaignId(), record.userId(), record.requestId()))
				.willReturn(CouponIssueCompensationResult.COMPENSATED);

		assertThatThrownBy(() -> coordinator.persist(
				record, IssueFailureStage.DB_INSERT, "incident-id"))
				.isInstanceOf(IllegalStateException.class);

		verify(issueProcessor).compensate(
				record.campaignId(), record.userId(), record.requestId());
		verify(failureRecorder).record(any());
	}

	@Test
	@DisplayName("같은 requestId의 다른 순번 행은 현재 요청이 저장된 것으로 보지 않는다")
	void reportsAbsentForDifferentSequence() {
		IssueRecord record = record(1L, 7L, 3L);
		given(couponIssueRepository.findByRequestId(record.requestId().toString()))
				.willReturn(Optional.of(stored(record.requestId(), 1L, 7L, 4)));

		assertThat(probe.probe(record)).isEqualTo(IssuePersistenceProbe.Result.ABSENT);
	}

	@Test
	@DisplayName("requestId 행이 없으면 현재 요청을 보상할 수 있도록 미저장으로 판단한다")
	void reportsAbsentWhenRequestDoesNotExist() {
		IssueRecord record = record(1L, 7L, 3L);
		given(couponIssueRepository.findByRequestId(record.requestId().toString()))
				.willReturn(Optional.empty());

		assertThat(probe.probe(record)).isEqualTo(IssuePersistenceProbe.Result.ABSENT);
	}

	@Test
	@DisplayName("DB 조회 실패 시 잘못된 보상을 막기 위해 판별 불가로 처리한다")
	void reportsUnverifiedWhenLookupFails() {
		IssueRecord record = record(1L, 7L, 3L);
		given(couponIssueRepository.findByRequestId(record.requestId().toString()))
				.willThrow(new IllegalStateException("DB 연결 실패"));

		assertThat(probe.probe(record)).isEqualTo(IssuePersistenceProbe.Result.UNVERIFIED);
	}

	private IssueRecord record(long campaignId, long userId, long sequence) {
		return new IssueRecord(
				UUID.randomUUID(),
				campaignId,
				userId,
				0L,
				userId - 1,
				sequence,
				Instant.parse("2026-08-19T06:00:00Z"),
				null);
	}

	private CouponIssue stored(
			UUID requestId,
			long campaignId,
			long userId,
			int sequence) {
		return CouponIssue.builder()
				.id(100L)
				.couponEvent(CouponEvent.builder().id(campaignId).build())
				.user(User.builder().id(userId).build())
				.issueSequence(sequence)
				.requestId(requestId.toString())
				.build();
	}
}
