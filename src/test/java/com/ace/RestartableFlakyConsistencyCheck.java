package com.ace;

import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.Scope;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

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

	public void throwOnNextCall() {
		shouldThrow.set(true);
	}

	@Override
	public Set<Scope.ScopeType> supportedScopeTypes() {
		return Set.of(Scope.ScopeType.ALL);
	}

	@Override
	public CheckOutcome check(Scope scope) {
		if (shouldThrow.compareAndSet(true, false)) {
			throw new RuntimeException("테스트용 강제 예외 (재시작 검증용)");
		}
		return CheckOutcome.pass();
	}
}
