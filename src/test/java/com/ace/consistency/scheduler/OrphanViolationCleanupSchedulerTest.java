package com.ace.consistency.scheduler;

import com.ace.consistency.repository.VerificationViolationRepository;
import com.ace.consistency.schedule.ConsistencySchedulerCoordinator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OrphanViolationCleanupSchedulerTest {

	@Test
	void 설정된_threshold만큼_오래_갱신되지_않은_고아_행을_정리한다() {
		VerificationViolationRepository repository = mock(VerificationViolationRepository.class);
		ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
		ConsistencySchedulerCoordinator coordinator = mock(ConsistencySchedulerCoordinator.class);
		OrphanViolationCleanupScheduler scheduler = new OrphanViolationCleanupScheduler(repository, eventPublisher, coordinator);
		ReflectionTestUtils.setField(scheduler, "orphanThresholdMinutes", 30L);
		LocalDateTime before = LocalDateTime.now().minusMinutes(30);

		scheduler.run();

		LocalDateTime after = LocalDateTime.now().minusMinutes(30);
		ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
		verify(repository).deleteOrphansStaleSince(captor.capture());
		assertThat(captor.getValue()).isBetween(before, after);
	}
}
