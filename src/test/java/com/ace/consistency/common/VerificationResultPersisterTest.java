package com.ace.consistency.common;

import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.entity.VerificationViolationEntity;
import com.ace.consistency.repository.VerificationResultRepository;
import com.ace.consistency.repository.VerificationViolationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class VerificationResultPersisterTest {

	@Mock VerificationResultRepository resultRepository;
	@Mock VerificationViolationRepository violationRepository;
	@Mock ApplicationEventPublisher eventPublisher;

	private VerificationResultPersister persister;

	@BeforeEach
	void setUp() {
		persister = new VerificationResultPersister(resultRepository, violationRepository, eventPublisher, new SimpleMeterRegistry());
	}

	@Test
	void 동기_실행은_결과_ID로_위반_행을_저장한다() {
		VerificationResultEntity saved = org.mockito.Mockito.mock(VerificationResultEntity.class);
		given(saved.getId()).willReturn(42L);
		given(resultRepository.saveAll(any())).willReturn(List.of(saved));
		VerificationResult result = failResult(List.of(
				violation(100L, "first"), violation(200L, "second")));

		List<VerificationResultEntity> returned = persister.saveAndNotify(
				List.of(result), result.getScope(), result.getTriggerType());

		assertThat(returned).containsExactly(saved);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<VerificationViolationEntity>> captor = ArgumentCaptor.forClass(List.class);
		verify(violationRepository).saveAll(captor.capture());
		assertThat(captor.getValue())
				.extracting(VerificationViolationEntity::getVerificationResultId)
				.containsExactly(42L, 42L);
		assertThat(captor.getValue())
				.extracting(VerificationViolationEntity::getTargetId)
				.containsExactly(100L, 200L);
	}

	@Test
	void 완료된_ALL_Step은_임시_위반을_결과에_연결한다() {
		VerificationResultEntity saved = org.mockito.Mockito.mock(VerificationResultEntity.class);
		given(saved.getId()).willReturn(77L);
		given(resultRepository.saveAll(any())).willReturn(List.of(saved));
		given(violationRepository.linkToResult(10L, "StockStep", 77L)).willReturn(2);

		VerificationResultEntity returned = persister.saveStepResult(
				failResultWithCount(2), 10L, "StockStep", false);

		assertThat(returned).isSameAs(saved);
		verify(violationRepository).linkToResult(10L, "StockStep", 77L);
	}

	@Test
	void 연결_건수가_누적_위반_건수와_다르면_예외를_던진다() {
		VerificationResultEntity saved = org.mockito.Mockito.mock(VerificationResultEntity.class);
		given(saved.getId()).willReturn(77L);
		given(resultRepository.saveAll(any())).willReturn(List.of(saved));
		given(violationRepository.linkToResult(10L, "StockStep", 77L)).willReturn(1);

		assertThatThrownBy(() -> persister.saveStepResult(
				failResultWithCount(2), 10L, "StockStep", false))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("expected=2")
				.hasMessageContaining("linked=1");
	}

	@Test
	void 실패한_ALL_Step은_임시_위반을_연결하거나_삭제하지_않는다() {
		VerificationResultEntity saved = org.mockito.Mockito.mock(VerificationResultEntity.class);
		given(resultRepository.saveAll(any())).willReturn(List.of(saved));

		persister.saveStepResult(VerificationResult.error(
				"StockConsistencyCheck", TriggerType.SCHEDULED, Scope.all(LocalDateTime.now()),
				new RuntimeException("failure"), LocalDateTime.now(), 1L), 10L, "StockStep", true);

		verify(violationRepository, never()).linkToResult(any(), any(), any());
	}

	private VerificationResult failResult(List<ConsistencyCheck.Violation> violations) {
		return VerificationResult.fail("StockConsistencyCheck", TriggerType.ON_DEMAND, Scope.ofEvent(1L),
				violations.size(), Map.of("violationCount", violations.size()), violations,
				LocalDateTime.now(), 1L);
	}

	private VerificationResult failResultWithCount(int count) {
		return VerificationResult.fail("StockConsistencyCheck", TriggerType.SCHEDULED, Scope.all(LocalDateTime.now()),
				count, Map.of("violationCount", count), List.of(), LocalDateTime.now(), 1L);
	}

	private ConsistencyCheck.Violation violation(long targetId, String reason) {
		return new ConsistencyCheck.Violation(
				ViolationTargetType.EVENT, targetId, Map.of("reason", reason));
	}
}
