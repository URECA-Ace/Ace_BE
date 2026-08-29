package com.ace.consistency.batch;

import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResult;
import com.ace.consistency.common.VerificationResultPersister;
import com.ace.event.consistency.ConsistencyStepStartedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Step(= Check 하나) 종료 시점에 실행되는 {@link StepExecutionListener}.
 * {@link CheckResultAccumulatorWriter}에 누적된 결과와 {@link StepExecution}의 성공/실패
 * 상태를 조합해 {@code VerificationResult}(pass/fail/error) 하나를 만들고,
 * {@code VerificationResultPersister}를 통해 즉시 저장한다. Job 전체가 끝나기를 기다리지 않고
 * Step 단위로 결과를 저장하므로, 뒤 Step이 실패해도 앞서 완료된 Check들의 결과는 남는다.
 */
@RequiredArgsConstructor
@Slf4j
public class ConsistencyStepCompletionListener implements StepExecutionListener {

    private final ConsistencyCheck check;
    private final CheckResultAccumulatorWriter writer;
    private final Scope scope;
    private final TriggerType triggerType;
    private final VerificationResultPersister resultPersister;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void beforeStep(StepExecution stepExecution) {
        eventPublisher.publishEvent(ConsistencyStepStartedEvent.builder()
                .checkName(check.getName())
                .triggerType(triggerType.name())
                .startedAt(LocalDateTime.now())
                .build());
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        try {
            LocalDateTime executedAt = stepExecution.getStartTime();
            long durationMillis = Duration.between(executedAt, LocalDateTime.now()).toMillis();

            VerificationResult result = buildResult(stepExecution, executedAt, durationMillis);
            // COMPLETED 이외의 상태(FAILED, STOPPED 등)는 재시작 가능한 미완료 Step이다.
            // 이 상태에서 임시 violation을 연결하면 재시작 시 누적 건수와 새로 연결할
            // 행의 건수가 달라질 수 있으므로, 완료된 Step에서만 연결한다.
            boolean stepIncomplete = stepExecution.getStatus() != BatchStatus.COMPLETED;

            // 완료 Step은 재시작 전후에 누적된 위반 행을 결과에 연결한다. 실패 Step의 행은
            // ExecutionContext와 함께 재시작에 사용해야 하므로 cleanup 유예 시간 동안 보존한다.
            //
            // afterStep()에서 발생한 예외는 Spring Batch가 로그만 남기고 삼키므로, 이 메서드
            // 전체에서 발생하는 런타임 예외를 잡아 Step을 직접 FAILED로 변경해야 한다.
            resultPersister.saveStepResult(
                    result,
                    stepExecution.getJobExecution().getJobInstance().getInstanceId(),
                    stepExecution.getStepName(),
                    stepIncomplete);

            return stepExecution.getExitStatus();
        } catch (RuntimeException ex) {
            log.error("배치 정합성 검증 에러 존재. jobExecutionId={}, stepExecutionId={}, stepName={}",
                    stepExecution.getJobExecution().getId(), stepExecution.getId(), stepExecution.getStepName(), ex);
            stepExecution.addFailureException(ex);
            stepExecution.upgradeStatus(BatchStatus.FAILED);
            return ExitStatus.FAILED.addExitDescription(ex);
        }
    }

    private VerificationResult buildResult(StepExecution stepExecution, LocalDateTime executedAt, long durationMillis) {
        if (stepExecution.getStatus() != BatchStatus.COMPLETED) {
            Throwable cause = stepExecution.getFailureExceptions().isEmpty()
                    ? new IllegalStateException("Step failed without recorded exception")
                    : stepExecution.getFailureExceptions().getFirst();
            return VerificationResult.error(check.getName(), triggerType, scope, cause, executedAt, durationMillis);
        }

        if (writer.isPass()) {
            return VerificationResult.pass(check.getName(), triggerType, scope, executedAt, durationMillis);
        }

        // 위반 행 자체는 writer가 청크마다 이미 verification_violation에 직접 저장해뒀으므로
        // (afterStep에서 batch owner로 연결), 여기서는 violations를 다시 채우지 않는다.
        Map<String, Object> diffDetail = Map.of("violationCount", writer.getViolationCount());
        return VerificationResult.fail(check.getName(), triggerType, scope,
                writer.getViolationCount(), diffDetail, List.of(), executedAt, durationMillis);
    }
}
