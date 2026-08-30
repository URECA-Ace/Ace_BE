package com.ace.consistency.scheduler;

import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.ConsistencyVerificationRunner;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResult;
import com.ace.consistency.repository.VerificationResultRepository;
import com.ace.consistency.schedule.ConsistencySchedulerCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AsOfRangeScopeConsistencySchedulerTest {

	private ConsistencyVerificationRunner runner;
	private VerificationResultRepository resultRepository;
	private ApplicationEventPublisher eventPublisher;
	private ConsistencySchedulerCoordinator coordinator;

	@BeforeEach
	void setUp() {
		runner = mock(ConsistencyVerificationRunner.class);
		resultRepository = mock(VerificationResultRepository.class);
		eventPublisher = mock(ApplicationEventPublisher.class);
		coordinator = mock(ConsistencySchedulerCoordinator.class);
	}

	private AsOfRangeScopeConsistencyScheduler newScheduler(List<ConsistencyCheck> checks) {
		AsOfRangeScopeConsistencyScheduler scheduler =
				new AsOfRangeScopeConsistencyScheduler(checks, runner, resultRepository, eventPublisher, coordinator);
		ReflectionTestUtils.setField(scheduler, "safetyMarginSeconds", 10L);
		ReflectionTestUtils.setField(scheduler, "initialLookbackHours", 24L);
		return scheduler;
	}

	private ConsistencyCheck fakeCheck(String name, Set<Scope.ScopeType> supportedTypes) {
		ConsistencyCheck check = mock(ConsistencyCheck.class);
		when(check.getName()).thenReturn(name);
		when(check.supportedScopeTypes()).thenReturn(supportedTypes);
		return check;
	}

	@Test
	@DisplayName("AS_OF_RANGE를 지원하지 않는 Check는 건너뛴다")
	void skipsChecksNotSupportingAsOfRange() {
		ConsistencyCheck asOfRangeCheck = fakeCheck("AsOfRangeCheck", Set.of(Scope.ScopeType.AS_OF_RANGE));
		ConsistencyCheck eventOnlyCheck = fakeCheck("EventOnlyCheck", Set.of(Scope.ScopeType.EVENT));
		when(resultRepository.findLastScopeTo(eq("AsOfRangeCheck"), any(), any())).thenReturn(Optional.empty());

		newScheduler(List.of(asOfRangeCheck, eventOnlyCheck)).run();

		verify(runner, times(1)).run(eq(List.of(asOfRangeCheck)), any(), eq(TriggerType.SCHEDULED));
		verify(resultRepository, never()).findLastScopeTo(eq("EventOnlyCheck"), any(), any());
	}

	@Test
	@DisplayName("이전 검사 기록이 없으면 initialLookbackHours만큼 과거부터 검사한다")
	void usesInitialLookbackWhenNoCursor() {
		ConsistencyCheck check = fakeCheck("AsOfRangeCheck", Set.of(Scope.ScopeType.AS_OF_RANGE));
		when(resultRepository.findLastScopeTo(eq("AsOfRangeCheck"), any(), any())).thenReturn(Optional.empty());

		newScheduler(List.of(check)).run();

		ArgumentCaptor<Scope> scopeCaptor = ArgumentCaptor.forClass(Scope.class);
		verify(runner).run(eq(List.of(check)), scopeCaptor.capture(), eq(TriggerType.SCHEDULED));
		Scope scope = scopeCaptor.getValue();
		assertEquals(24, Duration.between(scope.getFrom(), scope.getTo()).toHours());
	}

	@Test
	@DisplayName("이전 검사 기록이 있으면 그 시점부터 검사한다")
	void usesLastCursorWhenPresent() {
		ConsistencyCheck check = fakeCheck("AsOfRangeCheck", Set.of(Scope.ScopeType.AS_OF_RANGE));
		LocalDateTime lastCheckedAt = LocalDateTime.now().minusMinutes(5);
		when(resultRepository.findLastScopeTo(eq("AsOfRangeCheck"), any(), any())).thenReturn(Optional.of(lastCheckedAt));

		newScheduler(List.of(check)).run();

		ArgumentCaptor<Scope> scopeCaptor = ArgumentCaptor.forClass(Scope.class);
		verify(runner).run(eq(List.of(check)), scopeCaptor.capture(), eq(TriggerType.SCHEDULED));
		assertEquals(lastCheckedAt, scopeCaptor.getValue().getFrom());
	}

	@Test
	@DisplayName("마지막 검사 시점이 이번 틱의 to보다 미래거나 같으면 실행하지 않는다")
	void skipsWhenNoNewRangeToCheck() {
		ConsistencyCheck check = fakeCheck("AsOfRangeCheck", Set.of(Scope.ScopeType.AS_OF_RANGE));
		when(resultRepository.findLastScopeTo(eq("AsOfRangeCheck"), any(), any()))
				.thenReturn(Optional.of(LocalDateTime.now().plusDays(1)));

		newScheduler(List.of(check)).run();

		verify(runner, never()).run(any(), any(), any());
	}

	@Test
	@DisplayName("한 Check의 커서 조회 실패가 다른 Check 실행을 막지 않는다")
	void isolatesFailureBetweenChecks() {
		ConsistencyCheck failingCheck = fakeCheck("FailingCheck", Set.of(Scope.ScopeType.AS_OF_RANGE));
		ConsistencyCheck healthyCheck = fakeCheck("HealthyCheck", Set.of(Scope.ScopeType.AS_OF_RANGE));
		when(resultRepository.findLastScopeTo(eq("FailingCheck"), any(), any()))
				.thenThrow(new RuntimeException("DB 커넥션 실패"));
		when(resultRepository.findLastScopeTo(eq("HealthyCheck"), any(), any()))
				.thenReturn(Optional.empty());

		assertDoesNotThrow(() -> newScheduler(List.of(failingCheck, healthyCheck)).run());

		verify(runner, never()).run(eq(List.of(failingCheck)), any(), any());
		verify(runner, times(1)).run(eq(List.of(healthyCheck)), any(), eq(TriggerType.SCHEDULED));
	}

	@Test
	@DisplayName("runner.run() 실행 중 예외가 나도 스케줄러 밖으로 전파되지 않는다")
	void doesNotPropagateRunnerException() {
		ConsistencyCheck check = fakeCheck("AsOfRangeCheck", Set.of(Scope.ScopeType.AS_OF_RANGE));
		when(resultRepository.findLastScopeTo(eq("AsOfRangeCheck"), any(), any())).thenReturn(Optional.empty());
		doThrow(new RuntimeException("실행 실패")).when(runner).run(any(), any(), any());

		assertDoesNotThrow(() -> newScheduler(List.of(check)).run());
	}
}
