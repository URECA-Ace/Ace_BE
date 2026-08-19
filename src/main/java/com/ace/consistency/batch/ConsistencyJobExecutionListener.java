package com.ace.consistency.batch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.stereotype.Component;

/**
 * 정합성 검증 배치 Job 전체의 시작/종료를 관찰하는 {@link JobExecutionListener}.
 * 개별 Check(Step) 결과 저장은 {@link ConsistencyStepCompletionListener}가 이미 처리했으므로,
 * 이 리스너는 Job 단위로 최종 성공/실패 여부만 로깅하는 역할만 한다.
 */
@Component
@Slf4j
public class ConsistencyJobExecutionListener implements JobExecutionListener {

    @Override
    public void afterJob(JobExecution jobExecution) {
        if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
            log.info("Consistency verification batch completed. jobExecutionId={}, stepCount={}",
                    jobExecution.getId(), jobExecution.getStepExecutions().size());
        } else {
            String failedStepName = jobExecution.getStepExecutions().stream()
                    .filter(step -> step.getStatus() == BatchStatus.FAILED)
                    .findFirst()
                    .map(StepExecution::getStepName)
                    .orElse("unknown");
            log.warn("Consistency verification batch finished with status={}. jobExecutionId={}, failedStep={}",
                    jobExecution.getStatus(), jobExecution.getId(), failedStepName);
        }

        // TODO: 알림 이벤트 등록 (notify 도메인 머지 후 반영)
    }
}
