package com.ace.consistency.batch;

import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.TriggerType;
import com.ace.event.consistency.ConsistencyBatchCompletedEvent;
import com.ace.event.consistency.ConsistencyBatchStartedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 정합성 검증 배치 Job 전체의 시작/종료를 관찰하는 {@link JobExecutionListener}.
 * Job(Step 구성)마다 새로 만들어지므로({@link ConsistencyBatchJobFactory} 참고),
 * afterJob의 실패 로깅뿐 아니라 beforeJob에서 재시작에 필요한 checks/scope/triggerType을
 * ExecutionContext에 저장하는 책임도 함께 진다.
 *
 * beforeJob은 Job을 실행시킨 스레드가 아니라, 실제로 Step들을 실행하는 배치 스레드에서
 * Step보다 먼저 동기적으로 호출된다. 따라서 여기서 컨텍스트 저장에 실패하면 Job은 바로
 * FAILED로 끝나고 어떤 Step도 실행되지 않으므로, "컨텍스트 저장 실패 = 아직 아무 Step도
 * 시작하지 않음"이 항상 보장된다. (Job을 시작시킨 호출부 스레드에서 별도로 저장하면, 그
 * 스레드가 죽거나 저장에 실패하는 사이 배치 스레드는 이미 Step을 실행 중일 수 있어 이 보장이
 * 깨진다.)
 */
@RequiredArgsConstructor
@Slf4j
public class ConsistencyJobExecutionListener implements JobExecutionListener {

    public static final String RESTART_CHECK_NAMES_KEY = "consistency.restart.checkNames";
    public static final String RESTART_SCOPE_TO_KEY = "consistency.restart.scopeTo";
    public static final String RESTART_TRIGGER_TYPE_KEY = "consistency.restart.triggerType";
    public static final String RESTART_COMPLETED_CHECKS_KEY = "consistency.restart.completedChecks";
    public static final String CHECK_NAME_DELIMITER = ",";

    private final BatchFailureLogRepository failureLogRepository;
    private final JobRepository jobRepository;
    private final List<ConsistencyCheck> checks;
    private final LocalDateTime scopeTo;
    private final TriggerType triggerType;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void beforeJob(JobExecution jobExecution) {
        ExecutionContext context = jobExecution.getExecutionContext();
        context.putString(RESTART_CHECK_NAMES_KEY,
                checks.stream().map(ConsistencyCheck::getName).collect(Collectors.joining(CHECK_NAME_DELIMITER)));
        context.putString(RESTART_SCOPE_TO_KEY, scopeTo.toString());
        context.putString(RESTART_TRIGGER_TYPE_KEY, triggerType.name());
        jobRepository.updateExecutionContext(jobExecution);

        List<String> completedChecks = context.containsKey(RESTART_COMPLETED_CHECKS_KEY)
                ? List.of(context.getString(RESTART_COMPLETED_CHECKS_KEY).split(CHECK_NAME_DELIMITER))
                : List.of();

        // SCHEDULED/ON_DEMAND 트리거 종류와 무관하게 Job이 실제로 시작되는 지점이라,
        // 프론트가 "지금 배치가 도는 중"인지 알 수 있는 유일하고 일관된 발행 지점이다.
        eventPublisher.publishEvent(ConsistencyBatchStartedEvent.builder()
                .jobExecutionId(jobExecution.getId())
                .totalSteps(checks.size())
                .completedChecks(completedChecks)
                .triggerType(triggerType.name())
                .startedAt(LocalDateTime.now())
                .build());
    }

    // 이 클래스는 ConsistencyBatchJobFactory에서 new로 직접 만드는 일반 객체라 Spring 빈이
    // 아니다. 따라서 여기에 @Transactional을 붙여도 AOP 프록시를 타지 않아 아무 효과가 없고,
    // publishBatchCompletedEvent()에서 발행하는 이벤트는 실제로는 어떤 트랜잭션 안에서도
    // 실행되지 않는다 (VerificationResultPersister의 클래스 주석에 있는 것과 같은 함정).
    // 그래서 완료 알림 리스너(ConsistencyBatchCompletedNotifyListener)도 AFTER_COMMIT을
    // 기대하는 @TransactionalEventListener 대신 일반 @EventListener를 써야 한다.
    @Override
    public void afterJob(JobExecution jobExecution) {
        if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
            log.info("배치 정합성 검증 성공. jobExecutionId={}, stepCount={}",
                    jobExecution.getId(), jobExecution.getStepExecutions().size());
            publishBatchCompletedEvent(jobExecution);
            return;
        }

        String failedStepName = jobExecution.getStepExecutions().stream()
                .filter(step -> step.getStatus() == BatchStatus.FAILED)
                .findFirst()
                .map(StepExecution::getStepName)
                .orElse("unknown");
        log.warn("배치 정합성 검증 실패 존재. status={}. jobExecutionId={}, failedStep={}",
                jobExecution.getStatus(), jobExecution.getId(), failedStepName);

        failureLogRepository.save(BatchFailureLogEntity.from(jobExecution));

        publishBatchCompletedEvent(jobExecution);
    }

    private void publishBatchCompletedEvent(JobExecution jobExecution) {
        eventPublisher.publishEvent(ConsistencyBatchCompletedEvent.builder()
                .jobExecutionId(jobExecution.getId())
                .status(jobExecution.getStatus().name())
                .stepCount(jobExecution.getStepExecutions().size())
                .completedAt(LocalDateTime.now())
                .build());
    }
}
