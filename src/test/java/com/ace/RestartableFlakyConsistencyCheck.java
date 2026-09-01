package com.ace;

import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.ViolationTargetType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ConsistencyVerificationRunner.restartRunAsync() 통합 테스트 전용 Check.
 * 재시작은 실패했던 JobExecution의 ExecutionContext에 저장된 check 이름으로 Spring 빈을
 * 다시 조회해 Job을 재조립하므로, 테스트에서 직접 new 하는 익명 Check가 아니라 실제
 * @Component 빈으로 등록해야 한다. throwOnNextCall()을 호출해두면 바로 다음 check() 호출
 * 한 번만 예외를 던지고, 그 다음부터는 정상 pass()를 반환한다(재시작 후 성공 재현용).
 */
@Component
public class RestartableFlakyConsistencyCheck implements ConsistencyCheck {

	private final AtomicBoolean shouldThrow = new AtomicBoolean(false);
	private final AtomicInteger violationThenFailureStage = new AtomicInteger(0);
	private final AtomicBoolean multiPageMode = new AtomicBoolean(false);
	private final AtomicInteger multiPageCallCount = new AtomicInteger();

	public void throwOnNextCall() {
		shouldThrow.set(true);
	}

	/** 첫 페이지는 위반을 반환해 청크 커밋하고, 다음 페이지에서 예외를 발생시키는 재시작 테스트 모드. */
	public void failAfterCommittedViolation() {
		violationThenFailureStage.set(1);
	}

	/** 약 10개 청크에서 위반을 간헐적으로 누적한 뒤 다음 청크를 실패시키는 재시작 테스트 모드. */
	public void failAfterAccumulatingViolations() {
		multiPageCallCount.set(0);
		multiPageMode.set(true);
	}

	public void resetTestState() {
		shouldThrow.set(false);
		violationThenFailureStage.set(0);
		multiPageMode.set(false);
		multiPageCallCount.set(0);
	}

	@Override
	public Set<Scope.ScopeType> supportedScopeTypes() {
		return Set.of(Scope.ScopeType.ALL);
	}

	@Override
	public CheckOutcome check(Scope scope) {
		if (multiPageMode.get()) {
			int call = multiPageCallCount.incrementAndGet();
			if (call == 11) {
				throw new RuntimeException("여러 위반 청크 누적 이후 강제 실패");
			}
			if (call <= 10 && call % 2 == 1) {
				Long eventId = scope.getEventIds().getFirst();
				Violation violation = new Violation(
						ViolationTargetType.EVENT, eventId, Map.of("chunk", call));
				return CheckOutcome.fail(1, Map.of("violationCount", 1), List.of(violation));
			}
			return CheckOutcome.pass();
		}
		if (violationThenFailureStage.compareAndSet(1, 2)) {
			Long eventId = scope.getEventIds().getFirst();
			Violation violation = new Violation(
					ViolationTargetType.EVENT, eventId, Map.of("reason", "재시작 보존 테스트"));
			return CheckOutcome.fail(1, Map.of("violationCount", 1), List.of(violation));
		}
		if (violationThenFailureStage.compareAndSet(2, 3)) {
			throw new RuntimeException("첫 위반 청크 커밋 이후 강제 실패");
		}
		if (shouldThrow.compareAndSet(true, false)) {
			throw new RuntimeException("테스트용 강제 예외 (재시작 검증용)");
		}
		return CheckOutcome.pass();
	}
}
