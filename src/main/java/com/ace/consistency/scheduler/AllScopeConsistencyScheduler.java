package com.ace.consistency.scheduler;

import com.ace.consistency.batch.ConsistencyBatchJobFactory;
import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.ConsistencyVerificationRunner;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

	@Value("${consistency.all.safety-margin-seconds}")
	private long safetyMarginSeconds;

	@Scheduled(
			initialDelayString = "${consistency.all.fixed-delay-ms}",
			fixedDelayString = "${consistency.all.fixed-delay-ms}")
	public void run() {
		if (!jobRepository.findRunningJobExecutions(ConsistencyBatchJobFactory.JOB_NAME).isEmpty()) {
			log.info("이전 ALL 스코프 배치가 아직 실행 중이라 이번 틱은 건너뜁니다.");
			return;
		}

		try {
			LocalDateTime to = LocalDateTime.now().minusSeconds(safetyMarginSeconds);
			runner.runAsync(allChecks, Scope.all(to), TriggerType.SCHEDULED);
		} catch (Exception ex) {
			log.error("ALL 스코프 정합성 검증 배치 시작에 실패했습니다.", ex);
		}
	}
}
