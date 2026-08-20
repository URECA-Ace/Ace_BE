package com.ace.consistency.batch;

import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResult;
import com.ace.consistency.common.VerificationResultPersister;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;

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
public class ConsistencyStepCompletionListener implements StepExecutionListener {

    private final ConsistencyCheck check;
    private final CheckResultAccumulatorWriter writer;
    private final Scope scope;
    private final TriggerType triggerType;
    private final VerificationResultPersister resultPersister;

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        LocalDateTime executedAt = stepExecution.getStartTime();
        long durationMillis = Duration.between(executedAt, LocalDateTime.now()).toMillis();

        VerificationResult result = buildResult(stepExecution, executedAt, durationMillis);

        resultPersister.saveAndNotify(List.of(result), scope, triggerType);

        return stepExecution.getExitStatus();
    }

    private VerificationResult buildResult(StepExecution stepExecution, LocalDateTime executedAt, long durationMillis) {
        if (stepExecution.getStatus() == BatchStatus.FAILED) {
            Throwable cause = stepExecution.getFailureExceptions().isEmpty()
                    ? new IllegalStateException("Step failed without recorded exception")
                    : stepExecution.getFailureExceptions().getFirst();
            return VerificationResult.error(check.getName(), triggerType, scope, cause, executedAt, durationMillis);
        }

        if (writer.isPass()) {
            return VerificationResult.pass(check.getName(), triggerType, scope, executedAt, durationMillis);
        }

        Map<String, Object> diffDetail = Map.of(
                "violationCount", writer.getViolationCount(),
                "sample", writer.getSamples()
        );
        return VerificationResult.fail(check.getName(), triggerType, scope,
                writer.getViolationCount(), diffDetail, executedAt, durationMillis);
    }
}
