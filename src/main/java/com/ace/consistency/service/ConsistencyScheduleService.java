package com.ace.consistency.service;

import java.util.List;

import org.springframework.batch.core.repository.JobRepository;
import org.springframework.stereotype.Service;

import com.ace.common.ErrorCode;
import com.ace.common.exception.ConsistencyCheckException;
import com.ace.consistency.batch.ConsistencyBatchJobFactory;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.dto.response.ConsistencyScheduleResponse;
import com.ace.consistency.schedule.ConsistencySchedulerCoordinator;
import com.ace.consistency.schedule.ConsistencySchedulerNames;
import com.ace.consistency.schedule.ScheduleStatus;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ConsistencyScheduleService {

	private final ConsistencySchedulerCoordinator coordinator;
	private final JobRepository jobRepository;

	public List<ConsistencyScheduleResponse> findAll() {
		return coordinator.statuses().stream().map(this::toResponse).toList();
	}

	public ConsistencyScheduleResponse changeInterval(String schedulerName, long intervalMs) {
		coordinator.changeInterval(schedulerName, intervalMs);
		return coordinator.status(schedulerName)
				.map(this::toResponse)
				.orElseThrow(() -> new ConsistencyCheckException(ErrorCode.SCHEDULE_NOT_FOUND));
	}

	private ConsistencyScheduleResponse toResponse(ScheduleStatus status) {
		// ALL 스코프는 run()이 배치를 launch만 하고 바로 반환하므로, 실행 래핑 기반의 일반적인
		// running 플래그로는 "실제 배치가 끝났는지"를 알 수 없다. JobRepository로 직접 확인한다.
		boolean running = ConsistencySchedulerNames.ALL.equals(status.schedulerName())
				? isAllScopeBatchRunning()
				: status.running();
		return ConsistencyScheduleResponse.of(status, running);
	}

	private boolean isAllScopeBatchRunning() {
		return jobRepository.findRunningJobExecutions(ConsistencyBatchJobFactory.JOB_NAME).stream()
				.anyMatch(execution -> TriggerType.SCHEDULED.name().equals(execution.getJobParameters().getString("triggerType")));
	}
}
