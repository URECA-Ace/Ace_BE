package com.ace.consistency.rowlevel;

import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.ConsistencyVerificationRunner;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RowLevelConsistencyVerificationServiceTest {

	private final ConsistencyVerificationRunner runner = mock(ConsistencyVerificationRunner.class);
	private final RowLevelConsistencyCheckGroup checkGroup = mock(RowLevelConsistencyCheckGroup.class);
	private final RowLevelConsistencyVerificationService service =
			new RowLevelConsistencyVerificationService(runner, checkGroup);

	@Test
	void 구조_검증은_선택된_Check를_공통_Runner로_실행한다() {
		List<ConsistencyCheck> checks = List.of(mock(ConsistencyCheck.class));
		Scope scope = Scope.ofEvent(1L);
		when(checkGroup.structuralChecks()).thenReturn(checks);

		service.verifyStructural(scope, TriggerType.EVENT_TRIGGER);

		verify(runner).run(checks, scope, TriggerType.EVENT_TRIGGER);
	}

	@Test
	void 만료_검증은_허용경계와_검증시각을_AS_OF_RANGE로_전달한다() {
		List<ConsistencyCheck> checks = List.of(mock(ConsistencyCheck.class));
		LocalDateTime boundary = LocalDateTime.of(2026, 8, 14, 12, 55);
		LocalDateTime snapshot = boundary.plusMinutes(5);
		when(checkGroup.expirationChecks()).thenReturn(checks);

		service.verifyExpiration(boundary, snapshot, TriggerType.SCHEDULED);

		ArgumentCaptor<Scope> scopeCaptor = ArgumentCaptor.forClass(Scope.class);
		verify(runner).run(eq(checks), scopeCaptor.capture(), eq(TriggerType.SCHEDULED));
		assertThat(scopeCaptor.getValue().getType()).isEqualTo(Scope.ScopeType.AS_OF_RANGE);
		assertThat(scopeCaptor.getValue().getFrom()).isEqualTo(boundary);
		assertThat(scopeCaptor.getValue().getTo()).isEqualTo(snapshot);
	}
}
