package com.ace.consistency.scheduler;

import com.ace.consistency.batch.ConsistencyBatchJobFactory;
import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.ConsistencyVerificationRunner;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.event.scheduler.SchedulerStartedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
		prefix = "consistency.all",
		name = "enabled",
		havingValue = "true")
public class AllScopeConsistencyScheduler {
	private final List<ConsistencyCheck> allChecks;
	private final ConsistencyVerificationRunner runner;
	private final JobRepository jobRepository;
	private final ApplicationEventPublisher eventPublisher;

	private static final String SCHEDULER_NAME = "ALL_CONSISTENCY";

	@Value("${consistency.all.safety-margin-seconds}")
	private long safetyMarginSeconds;

	/**
	 * 이전 SCHEDULED ALL 스코프 배치가 아직 실행 중이면 이번 틱은 건너뛴다.
	 * runAsync()는 매 호출마다 서로 다른 runId로 새 JobInstance를 만들기 때문에,
	 * findRunningJobExecutions()는 JobName만으로 조회되어 트리거 종류를 구분하지 못한다.
	 * 그래서 JobParameters의 triggerType을 직접 걸러 "이 스케줄러가 이전에 시작한 실행"만 본다.
	 * (수동 실행(ON_DEMAND)은 스케줄러와 서로 막을 필요가 없어 의도적으로 제외한다)
	 *
	 * 이 체크는 Spring Boot 기본 스케줄링 풀 사이즈(1)에 의해 run()이 항상 단일 스레드에서
	 * 순차 호출된다는 전제 하에 안전하다. 스케줄링 풀을 늘리거나 멀티 인스턴스로 확장할 경우,
	 * 조회-실행 사이 race를 막기 위한 별도 락이 필요하다.
	 */
	@Scheduled(
			initialDelayString = "${consistency.all.fixed-delay-ms}",
			fixedDelayString = "${consistency.all.fixed-delay-ms}")
	public void run() {
		boolean previousScheduledRunInProgress = jobRepository.findRunningJobExecutions(ConsistencyBatchJobFactory.JOB_NAME)
				.stream()
				.anyMatch(execution -> TriggerType.SCHEDULED.name().equals(execution.getJobParameters().getString("triggerType")));

		if (previousScheduledRunInProgress) {
			log.info("이전 SCHEDULED ALL 스코프 배치가 아직 실행 중이라 이번 틱은 건너뜁니다.");
			return;
		}

		try {
			LocalDateTime to = LocalDateTime.now().minusSeconds(safetyMarginSeconds);
			// 배치 완료 알림은 afterJob(ConsistencyJobExecutionListener)에서 별도로 쏜다. runAsync()는
			// Job을 launch만 시키고 바로 반환하므로, 여기서 "종료"를 함께 알리면 실제 Step 실행이 끝나기도
			// 전에 완료로 오인될 수 있다.
			eventPublisher.publishEvent(SchedulerStartedEvent.builder()
					.schedulerName(SCHEDULER_NAME)
					.startedAt(LocalDateTime.now())
					.build());
			runner.runAsync(allChecks, Scope.all(to), TriggerType.SCHEDULED);
		} catch (Exception ex) {
			log.error("ALL 스코프 정합성 검증 배치 시작에 실패했습니다.", ex);
		}
	}
}
