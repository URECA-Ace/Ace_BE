package com.ace.consistency.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;

import com.ace.common.ErrorCode;
import com.ace.common.exception.ConsistencyCheckException;
import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.ConsistencyVerificationRunner;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResult;
import com.ace.consistency.dto.request.ConsistencyVerificationRequest;
import com.ace.consistency.dto.request.ConsistencyVerificationRequest.ScopeRequest;
import com.ace.consistency.dto.response.ConsistencyVerificationResponse;

class ConsistencyVerificationServiceTest {

	private final Clock clock = Clock.fixed(
			Instant.parse("2026-08-27T09:00:00Z"),
			ZoneId.of("Asia/Seoul"));
	private final ConsistencyVerificationRunner runner = mock(ConsistencyVerificationRunner.class);
	private final JobRepository jobRepository = mock(JobRepository.class);
	private final ConsistencyCheck eventAndAll = new FakeCheck(
			"EventAndAllCheck", "이벤트·전체 검사", Set.of(Scope.ScopeType.EVENT, Scope.ScopeType.ALL));
	private final ConsistencyCheck rangeCheck = new FakeCheck(
			"RangeCheck", "기간 검사", Set.of(Scope.ScopeType.AS_OF_RANGE));

	private ConsistencyVerificationService service;

	@BeforeEach
	void setUp() {
		service = new ConsistencyVerificationService(runner, List.of(eventAndAll, rangeCheck), clock, jobRepository);
	}

	@Test
	void Scope가_지원하는_검사와_한글_라벨을_반환한다() {
		var response = service.findSupportedChecks(Scope.ScopeType.EVENT);

		assertThat(response.scope().name()).isEqualTo("EVENT");
		assertThat(response.scope().label()).isEqualTo("특정 이벤트");
		assertThat(response.checks()).extracting("name", "label")
				.containsExactly(org.assertj.core.groups.Tuple.tuple("EventAndAllCheck", "이벤트·전체 검사"));
	}

	@Test
	void EVENT는_선택한_검사를_동기로_실행하고_전체_결과를_반환한다() {
		Scope expectedScope = Scope.ofEvent(10L);
		VerificationResult result = VerificationResult.pass(
				"EventAndAllCheck", TriggerType.ON_DEMAND, expectedScope, LocalDateTime.now(clock), 3L);
		given(runner.run(org.mockito.ArgumentMatchers.eq(List.of(eventAndAll)), any(Scope.class),
				org.mockito.ArgumentMatchers.eq(TriggerType.ON_DEMAND)))
				.willReturn(List.of(result));

		var response = service.verify(request(
				new ScopeRequest(Scope.ScopeType.EVENT, 10L, null, null),
				List.of("EventAndAllCheck")));

		assertThat(response.executionType()).isEqualTo(ConsistencyVerificationResponse.ExecutionType.SYNC);
		assertThat(response.results()).hasSize(1);
		assertThat(response.results().getFirst().status()).isEqualTo(VerificationResult.Status.PASS);
		verify(runner).run(org.mockito.ArgumentMatchers.eq(List.of(eventAndAll)), any(Scope.class),
				org.mockito.ArgumentMatchers.eq(TriggerType.ON_DEMAND));
	}

	@Test
	void ALL은_선택한_검사를_비동기로_실행하고_JobExecutionId를_반환한다() {
		JobExecution execution = mock(JobExecution.class);
		given(execution.getId()).willReturn(77L);
		given(runner.runAsync(
				org.mockito.ArgumentMatchers.eq(List.of(eventAndAll)),
				any(Scope.class),
				org.mockito.ArgumentMatchers.eq(TriggerType.ON_DEMAND))).willReturn(execution);

		var response = service.verify(request(
				new ScopeRequest(Scope.ScopeType.ALL, null, null, null),
				List.of("EventAndAllCheck")));

		assertThat(response.executionType()).isEqualTo(ConsistencyVerificationResponse.ExecutionType.ASYNC);
		assertThat(response.jobExecutionId()).isEqualTo(77L);
		assertThat(response.results()).isNull();
	}

	@Test
	void 요청_Scope를_지원하지_않는_검사가_하나라도_있으면_전체를_거부한다() {
		assertThatThrownBy(() -> service.verify(request(
				new ScopeRequest(Scope.ScopeType.EVENT, 10L, null, null),
				List.of("EventAndAllCheck", "RangeCheck"))))
				.isInstanceOfSatisfying(ConsistencyCheckException.class,
						exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER));
	}

	@Test
	void 중복된_검사는_전체를_거부한다() {
		assertThatThrownBy(() -> service.verify(request(
				new ScopeRequest(Scope.ScopeType.ALL, null, null, null),
				List.of("EventAndAllCheck", "EventAndAllCheck"))))
				.isInstanceOf(ConsistencyCheckException.class);
	}

	@Test
	void AS_OF_RANGE는_from보다_to가_뒤여야_한다() {
		LocalDateTime time = LocalDateTime.now(clock);

		assertThatThrownBy(() -> service.verify(request(
				new ScopeRequest(Scope.ScopeType.AS_OF_RANGE, null, time, time),
				List.of("RangeCheck"))))
				.isInstanceOf(ConsistencyCheckException.class);
	}

	private ConsistencyVerificationRequest request(ScopeRequest scope, List<String> checkNames) {
		return new ConsistencyVerificationRequest(scope, checkNames);
	}

	private record FakeCheck(
			String name,
			String label,
			Set<Scope.ScopeType> supportedScopeTypes) implements ConsistencyCheck {

		@Override
		public String getName() {
			return name;
		}

		@Override
		public String getLabel() {
			return label;
		}

		@Override
		public CheckOutcome check(Scope scope) {
			return CheckOutcome.pass();
		}
	}
}
