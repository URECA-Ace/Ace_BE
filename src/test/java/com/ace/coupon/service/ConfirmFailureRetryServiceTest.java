package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.RedisConnectionFailureException;

import com.ace.coupon.entity.IssueFailureLog;
import com.ace.coupon.persistence.failure.IssueFailureStage;
import com.ace.coupon.redis.CouponIssueConfirmResult;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.redis.RedisCouponIssueProcessor;
import com.ace.coupon.repository.IssueFailureLogRepository;
import com.ace.coupon.service.ConfirmFailureRetryService.SweepResult;

class ConfirmFailureRetryServiceTest {

	private IssueFailureLogRepository failureLogRepository;
	private RedisCouponIssueProcessor issueProcessor;
	private ConfirmFailureRetryService service;

	@BeforeEach
	void setUp() {
		failureLogRepository = Mockito.mock(IssueFailureLogRepository.class);
		issueProcessor = Mockito.mock(RedisCouponIssueProcessor.class);
		service = new ConfirmFailureRetryService(
				failureLogRepository,
				issueProcessor,
				new CouponIssueRedisProperties(Duration.ofDays(7), ZoneId.of("Asia/Seoul")));

		given(failureLogRepository.findRetryTargets(any(), any(), anyLong(), any()))
				.willReturn(List.of());
		given(failureLogRepository.findBlockedEventIds(any(), any())).willReturn(List.of());
	}

	@Test
	@DisplayName("재확인으로 확정되면 해소 시각을 기록한다")
	void resolvesWhenConfirmSucceeds() {
		IssueFailureLog failure = failure(1L);
		givenConfirm(failure, CouponIssueConfirmResult.CONFIRMED_NOW);

		assertThat(service.retry(failure)).isEqualTo(ConfirmFailureRetryResult.RESOLVED);

		assertThat(failure.isResolved()).isTrue();
		verify(failureLogRepository).save(failure);
	}

	@Test
	@DisplayName("이미 확정돼 있었으면 회수된 것으로 본다")
	void treatsAlreadyConfirmedAsRecovered() {
		// 확정은 CAS 안에서 카운터를 올리므로 중복 호출해도 값이 부풀지 않는다
		IssueFailureLog failure = failure(1L);
		givenConfirm(failure, CouponIssueConfirmResult.ALREADY_CONFIRMED);

		assertThat(service.retry(failure)).isEqualTo(ConfirmFailureRetryResult.ALREADY_RESOLVED);

		assertThat(failure.isResolved()).isTrue();
	}

	@Test
	@DisplayName("요청 레코드가 사라졌으면 만료로 분류하고 해소로 표시하지 않는다")
	void marksExpiredWhenRequestRecordIsGone() {
		// 시각 계산이 아니라 재확인 결과
		IssueFailureLog failure = failure(1L);
		givenConfirm(failure, CouponIssueConfirmResult.REQUEST_NOT_FOUND);

		assertThat(service.retry(failure)).isEqualTo(ConfirmFailureRetryResult.EXPIRED);

		assertThat(failure.isResolved()).isFalse();
		// 판정 값이 바뀌어 다음 주기 재처리 대상에서 빠진다
		assertThat(failure.getCompensationResult()).isEqualTo("REQUEST_NOT_FOUND");
		verify(failureLogRepository).save(failure);
	}

	@Test
	@DisplayName("되살릴 수 없는 상태면 확인 필요로 남기고 다시 집지 않는다")
	void marksNotRetryableAndStopsPicking() {
		IssueFailureLog failure = failure(1L);
		givenConfirm(failure, CouponIssueConfirmResult.CORRUPTED_STATE);

		assertThat(service.retry(failure)).isEqualTo(ConfirmFailureRetryResult.NOT_RETRYABLE);

		assertThat(failure.isResolved()).isFalse();
		assertThat(failure.getCompensationResult()).isEqualTo("CORRUPTED_STATE");
	}

	@Test
	@DisplayName("Redis 쓰기 실패는 판정 값을 그대로 둬 다음 주기에 다시 시도한다")
	void keepsTargetWhenWriteFails() {
		IssueFailureLog failure = failure(1L);
		givenConfirm(failure, CouponIssueConfirmResult.INTERNAL_WRITE_ERROR);

		assertThat(service.retry(failure)).isEqualTo(ConfirmFailureRetryResult.RETRY_FAILED);

		assertThat(failure.isResolved()).isFalse();
		assertThat(failure.getCompensationResult()).isEqualTo("CALL_FAILED");
		verify(failureLogRepository, never()).save(any());
	}

	@Test
	@DisplayName("확정 호출이 예외로 끝나도 다음 주기에 다시 시도한다")
	void keepsTargetWhenCallThrows() {
		IssueFailureLog failure = failure(1L);
		given(issueProcessor.confirm(any(), any(), any()))
				.willThrow(new RedisConnectionFailureException("down"));

		assertThat(service.retry(failure)).isEqualTo(ConfirmFailureRetryResult.RETRY_FAILED);

		verify(failureLogRepository, never()).save(any());
	}

	@Test
	@DisplayName("한 건의 실패가 다음 건 회수를 막지 않는다")
	void isolatesFailurePerRecord() {
		IssueFailureLog broken = failure(1L);
		IssueFailureLog healthy = failure(2L);
		givenTargets(broken, healthy);
		given(issueProcessor.confirm(
				broken.getEventId(), broken.getUserId(), UUID.fromString(broken.getRequestId())))
				.willThrow(new RedisConnectionFailureException("down"));
		givenConfirm(healthy, CouponIssueConfirmResult.CONFIRMED_NOW);

		SweepResult result = service.retryFailedConfirmations();

		assertThat(result.scanned()).isEqualTo(2);
		assertThat(result.recovered()).isOne();
		assertThat(result.retryFailed()).isOne();
		assertThat(healthy.isResolved()).isTrue();
	}

	@Test
	@DisplayName("회수 사유별 건수와 막힌 회차를 함께 보고한다")
	void reportsCountsByReasonWithBlockedEvents() {
		// 조용히 넘기면 회차가 왜 마감되지 않는지 알 수 없다
		IssueFailureLog resolved = failure(1L);
		IssueFailureLog expired = failure(2L);
		givenTargets(resolved, expired);
		givenConfirm(resolved, CouponIssueConfirmResult.CONFIRMED_NOW);
		givenConfirm(expired, CouponIssueConfirmResult.REQUEST_NOT_FOUND);
		given(failureLogRepository.countUnrecoverable(any(), any())).willReturn(3L);
		given(failureLogRepository.findBlockedEventIds(any(), any())).willReturn(List.of(77L));

		SweepResult result = service.retryFailedConfirmations();

		assertThat(result.resolved()).isOne();
		assertThat(result.expired()).isOne();
		assertThat(result.unrecoverable()).isEqualTo(3L);
		assertThat(result.blockedEventIds()).containsExactly(77L);
		assertThat(result.hasUnrecovered()).isTrue();
	}

	@Test
	@DisplayName("전부 회수되고 막힌 회차가 없으면 이상으로 보지 않는다")
	void reportsNoAnomalyWhenEverythingRecovered() {
		IssueFailureLog failure = failure(1L);
		givenTargets(failure);
		givenConfirm(failure, CouponIssueConfirmResult.CONFIRMED_NOW);

		assertThat(service.retryFailedConfirmations().hasUnrecovered()).isFalse();
	}

	@Test
	@DisplayName("대상이 한 페이지를 넘으면 커서로 다음 페이지까지 훑는다")
	void sweepsBeyondTheFirstPage() {
		// 재시도 가능한 건만 남아도 계속 실패하면 앞 페이지가 채워진다
		List<IssueFailureLog> firstPage = new ArrayList<>();
		for (long id = 1; id <= 100; id++) {
			firstPage.add(failure(id));
		}
		IssueFailureLog lastOne = failure(101L);
		given(failureLogRepository.findRetryTargets(
				any(), any(), Mockito.eq(0L), any()))
				.willReturn(firstPage);
		given(failureLogRepository.findRetryTargets(
				any(), any(), Mockito.eq(100L), any()))
				.willReturn(List.of(lastOne));
		given(issueProcessor.confirm(any(), any(), any()))
				.willReturn(CouponIssueConfirmResult.CONFIRMED_NOW);

		SweepResult result = service.retryFailedConfirmations();

		assertThat(result.scanned()).isEqualTo(101);
		assertThat(lastOne.isResolved()).isTrue();
	}

	@Test
	@DisplayName("회수할 대상이 없으면 경보 집계 쿼리를 돌리지 않는다")
	void skipsAlertQueriesWhenNothingToRetry() {
		SweepResult result = service.retryFailedConfirmations();

		assertThat(result.scanned()).isZero();
		assertThat(result.hasUnrecovered()).isFalse();
		verify(failureLogRepository, never()).countUnrecoverable(any(), any());
		verify(failureLogRepository, never()).findBlockedEventIds(any(), any());
	}

	@Test
	@DisplayName("같은 상태가 이어지면 경보를 다시 남기지 않는다")
	void doesNotRepeatIdenticalAlert() {
		// 매번 남기면 주기마다 같은 경보가 쌓인다
		IssueFailureLog first = failure(1L);
		givenTargets(first);
		givenConfirm(first, CouponIssueConfirmResult.CORRUPTED_STATE);
		given(failureLogRepository.countUnrecoverable(any(), any())).willReturn(1L);
		given(failureLogRepository.findBlockedEventIds(any(), any())).willReturn(List.of(101L));

		SweepResult firstSweep = service.retryFailedConfirmations();
		SweepResult secondSweep = service.retryFailedConfirmations();

		// 상태가 같으므로 두 결과의 경보 내용이 동일하다
		assertThat(firstSweep.hasUnrecovered()).isTrue();
		assertThat(secondSweep.hasUnrecovered()).isTrue();
		assertThat(secondSweep.blockedEventIds()).isEqualTo(firstSweep.blockedEventIds());
	}

	private void givenTargets(IssueFailureLog... failures) {
		given(failureLogRepository.findRetryTargets(any(), any(), Mockito.eq(0L), any()))
				.willReturn(List.of(failures));
	}

	private void givenConfirm(IssueFailureLog failure, CouponIssueConfirmResult result) {
		given(issueProcessor.confirm(
				failure.getEventId(), failure.getUserId(), UUID.fromString(failure.getRequestId())))
				.willReturn(result);
	}

	private IssueFailureLog failure(long id) {
		return IssueFailureLog.builder()
				.id(id)
				.eventId(100L + id)
				.userId(200L + id)
				.requestId(UUID.randomUUID().toString())
				.issueSequence(id)
				.failureStage(IssueFailureStage.CONFIRM)
				.compensationResult("CALL_FAILED")
				.errorMessage("테스트")
				.incidentId(UUID.randomUUID().toString())
				.occurredAt(LocalDateTime.now())
				.resolvedAt(null)
				.build();
	}
}
