package com.ace.consistency.scheduler;

import com.ace.consistency.common.*;
import com.ace.consistency.repository.VerificationResultRepository;
import com.ace.event.scheduler.SchedulerCompletedEvent;
import com.ace.event.scheduler.SchedulerStartedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
		prefix = "consistency.as-of-range",
		name = "enabled",
		havingValue = "true")
public class AsOfRangeScopeConsistencyScheduler {
	private final List<ConsistencyCheck> allChecks;
	private final ConsistencyVerificationRunner runner;
	private final VerificationResultRepository resultRepository;
	private final ApplicationEventPublisher eventPublisher;

	private static final String SCHEDULER_NAME = "AS_OF_RANGE_CONSISTENCY";

	@Value("${consistency.as-of-range.safety-margin-seconds}")
	private long safetyMarginSeconds;

	@Value("${consistency.as-of-range.initial-lookback-hours}")
	private long initialLookbackHours;

	@Scheduled(
			initialDelayString = "${consistency.as-of-range.fixed-delay-ms}",
			fixedDelayString = "${consistency.as-of-range.fixed-delay-ms}")
	public void run() {
		eventPublisher.publishEvent(SchedulerStartedEvent.builder()
				.schedulerName(SCHEDULER_NAME)
				.startedAt(LocalDateTime.now())
				.build());

		LocalDateTime to = LocalDateTime.now().minusSeconds(safetyMarginSeconds);
		int checksRun = 0;
		for (ConsistencyCheck check : allChecks) {
			if (!check.supportedScopeTypes().contains(Scope.ScopeType.AS_OF_RANGE)) {
				continue;
			}
			runOne(check, to);
			checksRun++;
		}

		eventPublisher.publishEvent(SchedulerCompletedEvent.builder()
				.schedulerName(SCHEDULER_NAME)
				.result(Map.of("checksRun", checksRun))
				.completedAt(LocalDateTime.now())
				.build());
	}

	private void runOne(ConsistencyCheck check, LocalDateTime to) {
		try {
			LocalDateTime from = resultRepository
					.findLastScopeTo(check.getName(), Scope.ScopeType.AS_OF_RANGE, VerificationResult.Status.ERROR)
					.orElse(to.minusHours(initialLookbackHours));

			if (!from.isBefore(to)) {
				return; // 아직 다음 틱까지 볼 구간이 없음
			}

			runner.run(List.of(check), Scope.ofAsOfRange(from, to), TriggerType.SCHEDULED);
		} catch (Exception ex) {
			// runner.run() 내부에서 Check별 예외는 이미 ERROR 결과로 변환되어 저장되므로,
			// 여기서 잡는 예외는 그 이전 단계(EVENT 존재 검증 등 run() 자체의 사전 검증) 실패다.
			// 한 Check의 사전 검증 실패가 다른 Check의 이번 틱 실행을 막지 않도록 격리한다.
			log.error("AS_OF_RANGE scheduled run failed before check execution. checkName={}, to={}",
					check.getName(), to, ex);
		}
	}
}
