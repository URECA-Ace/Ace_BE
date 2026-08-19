package com.ace.coupon.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ace.coupon.redis.CouponIssueCompensationResult;
import com.ace.coupon.redis.RedisCouponIssueProcessor;
import com.ace.coupon.persistence.failure.IssueFailure;
import com.ace.coupon.persistence.failure.IssueFailureRecorder;
import com.ace.coupon.persistence.failure.IssueFailureStage;

@ExtendWith(MockitoExtension.class)
class IssuePersistenceCoordinatorTest {

	private static final String INCIDENT_ID = "11111111-2222-3333-4444-555555555555";

	@Mock
	private IssuePersistenceService persistenceService;

	@Mock
	private IssuePersistenceProbe persistenceProbe;

	@Mock
	private RedisCouponIssueProcessor issueProcessor;

	@Mock
	private IssueFailureRecorder failureRecorder;

	@InjectMocks
	private IssuePersistenceCoordinator coordinator;

	private final IssueRecord record = new IssueRecord(
			UUID.randomUUID(), 1L, 7L, 0L, 6L, 3L, Instant.ofEpochMilli(1_755_000_000_000L), null);

	// 실패 케이스의 기본 전제: 저장은 정말로 안 됐다
	private void givenNotPersisted() {
		given(persistenceProbe.probe(record)).willReturn(IssuePersistenceProbe.Result.ABSENT);
	}

	@Test
	@DisplayName("저장에 성공하면 원복하지 않는다")
	void doesNotCompensateOnSuccess() {
		given(persistenceService.persist(record)).willReturn(42L);

		assertThat(coordinator.persist(record, IssueFailureStage.DB_INSERT, INCIDENT_ID)).isEqualTo(42L);

		verify(issueProcessor, never()).compensate(anyLong(), anyLong(), any());
		verify(failureRecorder, never()).record(any());
	}

	@Test
	@DisplayName("저장에 실패하면 재고를 되돌리고 실패를 기록한다")
	void compensatesAndRecordsOnFailure() {
		givenNotPersisted();
		given(persistenceService.persist(record)).willThrow(new IllegalStateException("저장 실패"));
		given(issueProcessor.compensate(1L, 7L, record.requestId()))
				.willReturn(CouponIssueCompensationResult.COMPENSATED);

		assertThatThrownBy(() -> coordinator.persist(record, IssueFailureStage.DB_INSERT, INCIDENT_ID))
				.isInstanceOf(IllegalStateException.class);

		ArgumentCaptor<IssueFailure> captor = ArgumentCaptor.forClass(IssueFailure.class);
		verify(failureRecorder).record(captor.capture());
		IssueFailure failure = captor.getValue();
		assertThat(failure.stage()).isEqualTo(IssueFailureStage.DB_INSERT);
		assertThat(failure.compensationResult()).isEqualTo("COMPENSATED");
		assertThat(failure.incidentId()).isEqualTo(INCIDENT_ID);
		assertThat(failure.requestId()).isEqualTo(record.requestId().toString());
		assertThat(failure.issueSequence()).isEqualTo(3L);
		assertThat(failure.errorMessage()).contains("저장 실패");
	}

	@Test
	@DisplayName("원본 예외를 그대로 올린다")
	void rethrowsOriginalFailure() {
		givenNotPersisted();
		IllegalStateException cause = new IllegalStateException("저장 실패");
		given(persistenceService.persist(record)).willThrow(cause);
		given(issueProcessor.compensate(anyLong(), anyLong(), any()))
				.willReturn(CouponIssueCompensationResult.COMPENSATED);

		assertThatThrownBy(() -> coordinator.persist(record, IssueFailureStage.RELAY, INCIDENT_ID))
				.isSameAs(cause);
	}

	@Test
	@DisplayName("보상까지 실패하면 두 줄로 남긴다")
	void recordsTwiceWhenCompensationFails() {
		givenNotPersisted();
		given(persistenceService.persist(record)).willThrow(new IllegalStateException("저장 실패"));
		given(issueProcessor.compensate(anyLong(), anyLong(), any()))
				.willThrow(new IllegalStateException("Redis 연결 실패"));

		assertThatThrownBy(() -> coordinator.persist(record, IssueFailureStage.DB_INSERT, INCIDENT_ID))
				.isInstanceOf(IllegalStateException.class);

		ArgumentCaptor<IssueFailure> captor = ArgumentCaptor.forClass(IssueFailure.class);
		verify(failureRecorder, times(2)).record(captor.capture());
		List<IssueFailure> failures = captor.getAllValues();

		assertThat(failures).extracting(IssueFailure::stage)
				.containsExactly(IssueFailureStage.COMPENSATE, IssueFailureStage.DB_INSERT);
		assertThat(failures.get(0).errorMessage()).contains("Redis 연결 실패");
		assertThat(failures.get(1).compensationResult()).isEqualTo("CALL_FAILED");
	}

	@Test
	@DisplayName("이미 보상된 요청도 그대로 기록한다")
	void recordsAlreadyCompensated() {
		givenNotPersisted();
		given(persistenceService.persist(record)).willThrow(new IllegalStateException("저장 실패"));
		given(issueProcessor.compensate(anyLong(), anyLong(), any()))
				.willReturn(CouponIssueCompensationResult.ALREADY_COMPENSATED);

		assertThatThrownBy(() -> coordinator.persist(record, IssueFailureStage.DB_INSERT, INCIDENT_ID))
				.isInstanceOf(IllegalStateException.class);

		ArgumentCaptor<IssueFailure> captor = ArgumentCaptor.forClass(IssueFailure.class);
		verify(failureRecorder).record(captor.capture());
		assertThat(captor.getValue().compensationResult()).isEqualTo("ALREADY_COMPENSATED");
	}

	@Test
	@DisplayName("저장이 확인되면 되돌리지 않는다 - 커밋 후 예외였을 수 있고, 되돌리면 초과 발급이 된다")
	void doesNotCompensateWhenAlreadyPersisted() {
		given(persistenceService.persist(record)).willThrow(new IllegalStateException("연결 끊김"));
		given(persistenceProbe.probe(record)).willReturn(IssuePersistenceProbe.Result.PERSISTED);

		assertThatThrownBy(() -> coordinator.persist(record, IssueFailureStage.DB_INSERT, INCIDENT_ID))
				.isInstanceOf(IllegalStateException.class);

		verify(issueProcessor, never()).compensate(anyLong(), anyLong(), any());

		ArgumentCaptor<IssueFailure> captor = ArgumentCaptor.forClass(IssueFailure.class);
		verify(failureRecorder).record(captor.capture());
		assertThat(captor.getValue().compensationResult()).isEqualTo("SKIPPED_PERSISTED");
	}

	@Test
	@DisplayName("저장 여부를 판별할 수 없으면 되돌리지 않고 복구 대상으로 남긴다")
	void doesNotCompensateWhenUnverified() {
		given(persistenceService.persist(record)).willThrow(new IllegalStateException("저장 실패"));
		given(persistenceProbe.probe(record)).willReturn(IssuePersistenceProbe.Result.UNVERIFIED);

		assertThatThrownBy(() -> coordinator.persist(record, IssueFailureStage.DB_INSERT, INCIDENT_ID))
				.isInstanceOf(IllegalStateException.class);

		verify(issueProcessor, never()).compensate(anyLong(), anyLong(), any());

		ArgumentCaptor<IssueFailure> captor = ArgumentCaptor.forClass(IssueFailure.class);
		verify(failureRecorder).record(captor.capture());
		assertThat(captor.getValue().compensationResult()).isEqualTo("SKIPPED_UNVERIFIED");
	}

	@Test
	@DisplayName("원복 결과가 불확실하면 null 을 돌려준다 - 호출부가 ACK 를 미룰 수 있어야 한다")
	void returnsNullWhenCompensationOutcomeUnknown() {
		givenNotPersisted();
		given(issueProcessor.compensate(anyLong(), anyLong(), any()))
				.willThrow(new IllegalStateException("Redis 연결 실패"));

		CouponIssueCompensationResult result = coordinator.abandon(
				record, IssueFailureStage.RELAY, INCIDENT_ID, new IllegalStateException("저장 실패"));

		assertThat(result).isNull();
	}

	@Test
	@DisplayName("원복에 성공하면 결과를 돌려준다")
	void returnsCompensationResult() {
		givenNotPersisted();
		given(issueProcessor.compensate(anyLong(), anyLong(), any()))
				.willReturn(CouponIssueCompensationResult.COMPENSATED);

		CouponIssueCompensationResult result = coordinator.abandon(
				record, IssueFailureStage.RELAY, INCIDENT_ID, new IllegalStateException("저장 실패"));

		assertThat(result).isEqualTo(CouponIssueCompensationResult.COMPENSATED);
	}
}
