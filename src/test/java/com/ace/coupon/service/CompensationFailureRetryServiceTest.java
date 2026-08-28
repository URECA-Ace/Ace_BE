package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import com.ace.coupon.entity.IssueFailureLog;
import com.ace.coupon.persistence.IssuePersistenceCoordinator;
import com.ace.coupon.persistence.IssuePersistenceProbe;
import com.ace.coupon.persistence.failure.IssueFailureStage;
import com.ace.coupon.redis.CouponIssueCompensationResult;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.redis.RedisCouponIssueProcessor;
import com.ace.coupon.repository.IssueFailureLogRepository;

@ExtendWith(MockitoExtension.class)
class CompensationFailureRetryServiceTest {

	private static final long EVENT_ID = 51L;
	private static final long USER_ID = 7L;
	private static final String REQUEST_ID = "3f1a2b4c-5d6e-4f70-8a91-b2c3d4e5f607";

	@Mock
	private IssueFailureLogRepository failureLogRepository;

	@Mock
	private IssuePersistenceProbe persistenceProbe;

	@Mock
	private RedisCouponIssueProcessor issueProcessor;

	private CompensationFailureRetryService service;

	@BeforeEach
	void setUp() {
		service = new CompensationFailureRetryService(
				failureLogRepository,
				persistenceProbe,
				issueProcessor,
				new CouponIssueRedisProperties(Duration.ofDays(7), ZoneId.of("Asia/Seoul")),
				100);
	}

	@Test
	@DisplayName("저장되지 않은 건은 재고를 되돌리고 해소로 표시한다")
	void compensatesAbsentIssueAndResolves() {
		IssueFailureLog failure = failure(IssuePersistenceCoordinator.CALL_FAILED);
		given(persistenceProbe.probe(EVENT_ID, USER_ID, REQUEST_ID, 12L))
				.willReturn(IssuePersistenceProbe.Result.ABSENT);
		given(issueProcessor.compensate(EVENT_ID, USER_ID, UUID.fromString(REQUEST_ID)))
				.willReturn(CouponIssueCompensationResult.COMPENSATED);

		CompensationFailureRetryResult result = service.retry(failure);

		assertThat(result).isEqualTo(CompensationFailureRetryResult.RESOLVED);
		assertThat(result.isRecovered()).isTrue();
		// 해소로 표시돼야 다음 주기에 다시 잡히지 않는다
		assertThat(failure.isResolved()).isTrue();
		assertThat(failure.getAttemptCount()).isEqualTo(1);
		verify(failureLogRepository).save(failure);
	}

	@Test
	@DisplayName("이미 원복된 건도 해소로 표시한다")
	void treatsAlreadyCompensatedAsRecovered() {
		IssueFailureLog failure = failure(IssuePersistenceCoordinator.CALL_FAILED);
		given(persistenceProbe.probe(EVENT_ID, USER_ID, REQUEST_ID, 12L))
				.willReturn(IssuePersistenceProbe.Result.ABSENT);
		given(issueProcessor.compensate(anyLong(), anyLong(), any()))
				.willReturn(CouponIssueCompensationResult.ALREADY_COMPENSATED);

		CompensationFailureRetryResult result = service.retry(failure);

		assertThat(result).isEqualTo(CompensationFailureRetryResult.ALREADY_RESOLVED);
		assertThat(result.isRecovered()).isTrue();
		assertThat(failure.isResolved()).isTrue();
	}

	@Test
	@DisplayName("이미 저장된 건은 되돌리지 않는다. 되돌리면 초과 발급이 된다")
	void neverCompensatesPersistedIssue() {
		IssueFailureLog failure = failure(IssuePersistenceCoordinator.CALL_FAILED);
		given(persistenceProbe.probe(EVENT_ID, USER_ID, REQUEST_ID, 12L))
				.willReturn(IssuePersistenceProbe.Result.PERSISTED);

		CompensationFailureRetryResult result = service.retry(failure);

		assertThat(result).isEqualTo(CompensationFailureRetryResult.SKIPPED_PERSISTED);
		// 보상 Lua 를 부르면 MySQL 에 행이 있는 채로 재고가 복구돼 초과 발급이 된다
		verify(issueProcessor, never()).compensate(anyLong(), anyLong(), any());
		// 해소는 아니지만 판정 값이 바뀌어 재처리 대상에서 빠진다
		assertThat(failure.isResolved()).isFalse();
		assertThat(failure.getCompensationResult())
				.isEqualTo(IssuePersistenceCoordinator.COMPENSATION_SKIPPED_PERSISTED);
	}

	@Test
	@DisplayName("저장 여부를 판별하지 못하면 되돌리지 않고 다음 주기로 미룬다")
	void postponesWhenPersistenceIsUnverified() {
		IssueFailureLog failure = failure(IssuePersistenceCoordinator.COMPENSATION_SKIPPED_UNVERIFIED);
		given(persistenceProbe.probe(EVENT_ID, USER_ID, REQUEST_ID, 12L))
				.willReturn(IssuePersistenceProbe.Result.UNVERIFIED);

		CompensationFailureRetryResult result = service.retry(failure);

		assertThat(result).isEqualTo(CompensationFailureRetryResult.RETRY_FAILED);
		verify(issueProcessor, never()).compensate(anyLong(), anyLong(), any());
		// 판정 값을 바꾸지 않아야 다음 주기에 다시 잡힌다
		assertThat(failure.getCompensationResult())
				.isEqualTo(IssuePersistenceCoordinator.COMPENSATION_SKIPPED_UNVERIFIED);
	}

	@Test
	@DisplayName("요청 레코드가 사라졌으면 되살릴 수 없는 건으로 표시한다")
	void marksExpiredWhenRequestRecordIsGone() {
		IssueFailureLog failure = failure(IssuePersistenceCoordinator.CALL_FAILED);
		given(persistenceProbe.probe(EVENT_ID, USER_ID, REQUEST_ID, 12L))
				.willReturn(IssuePersistenceProbe.Result.ABSENT);
		given(issueProcessor.compensate(anyLong(), anyLong(), any()))
				.willReturn(CouponIssueCompensationResult.REQUEST_NOT_FOUND);

		CompensationFailureRetryResult result = service.retry(failure);

		assertThat(result).isEqualTo(CompensationFailureRetryResult.EXPIRED);
		assertThat(result.needsAttention()).isTrue();
		assertThat(failure.isResolved()).isFalse();
		assertThat(failure.getCompensationResult())
				.isEqualTo(CouponIssueCompensationResult.REQUEST_NOT_FOUND.name());
	}

	@Test
	@DisplayName("Redis 쓰기 실패는 판정 값을 그대로 둬 다음 주기에 다시 잡히게 한다")
	void keepsWriteErrorRetryable() {
		IssueFailureLog failure = failure(IssuePersistenceCoordinator.CALL_FAILED);
		given(persistenceProbe.probe(EVENT_ID, USER_ID, REQUEST_ID, 12L))
				.willReturn(IssuePersistenceProbe.Result.ABSENT);
		given(issueProcessor.compensate(anyLong(), anyLong(), any()))
				.willReturn(CouponIssueCompensationResult.INTERNAL_WRITE_ERROR);

		CompensationFailureRetryResult result = service.retry(failure);

		assertThat(result).isEqualTo(CompensationFailureRetryResult.RETRY_FAILED);
		assertThat(failure.getCompensationResult())
				.isEqualTo(IssuePersistenceCoordinator.CALL_FAILED);
	}

	@Test
	@DisplayName("호출이 예외로 끝나도 시도 기록을 남기고 다음 건으로 넘어간다")
	void recordsAttemptEvenWhenCallThrows() {
		IssueFailureLog failure = failure(IssuePersistenceCoordinator.CALL_FAILED);
		given(persistenceProbe.probe(EVENT_ID, USER_ID, REQUEST_ID, 12L))
				.willThrow(new DataAccessResourceFailureException("redis down"));

		CompensationFailureRetryResult result = service.retry(failure);

		assertThat(result).isEqualTo(CompensationFailureRetryResult.RETRY_FAILED);
		assertThat(failure.getAttemptCount()).isEqualTo(1);
		verify(failureLogRepository).save(failure);
	}

	@Test
	@DisplayName("CONFIRM 이 아닌 세 단계를 재처리 대상으로 조회한다")
	void sweepsCompensationStagesOnly() {
		given(failureLogRepository.findRetryTargets(any(), any(), any())).willReturn(List.of());
		given(failureLogRepository.countUnrecoverable(any(), any())).willReturn(0L);
		given(failureLogRepository.findBlockedEventIds(any(), any())).willReturn(List.of());

		CompensationFailureRetryService.SweepResult result = service.retryFailedCompensations();

		assertThat(result.scanned()).isZero();
		verify(failureLogRepository).findRetryTargets(
				argThat(stages -> stages.containsAll(List.of(
						IssueFailureStage.COMPENSATE,
						IssueFailureStage.DB_INSERT,
						IssueFailureStage.RELAY))
						&& !stages.contains(IssueFailureStage.CONFIRM)),
				any(),
				any());
	}

	@Test
	@DisplayName("되살릴 수 없는 건만 남아도 막힌 회차를 보고한다")
	void reportsBlockedEventsEvenWhenNothingIsRetryable() {
		given(failureLogRepository.findRetryTargets(any(), any(), any())).willReturn(List.of());
		given(failureLogRepository.countUnrecoverable(any(), any())).willReturn(2L);
		given(failureLogRepository.findBlockedEventIds(any(), any())).willReturn(List.of(EVENT_ID));

		CompensationFailureRetryService.SweepResult result = service.retryFailedCompensations();

		// 재시도 대상이 없다는 것과 막힌 회차가 없다는 것은 다르다
		assertThat(result.scanned()).isZero();
		assertThat(result.hasUnrecovered()).isTrue();
		assertThat(result.blockedEventIds()).containsExactly(EVENT_ID);
	}

	private IssueFailureLog failure(String compensationResult) {
		return IssueFailureLog.builder()
				.id(1L)
				.eventId(EVENT_ID)
				.userId(USER_ID)
				.requestId(REQUEST_ID)
				.issueSequence(12L)
				.failureStage(IssueFailureStage.COMPENSATE)
				.compensationResult(compensationResult)
				.occurredAt(LocalDateTime.of(2026, 8, 28, 12, 0))
				.build();
	}
}
