package com.ace.consistency.batch;

import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.Scope;
import com.ace.event.consistency.ConsistencyStepProgressEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link EventIdPageReader}가 읽은 event_id 페이지 하나를 받아, 이 Step에 지정된
 * {@link ConsistencyCheck} 하나를 {@code Scope.ALL}로 실행하는 {@link ItemProcessor}.
 * Check 자체는 상태가 없으므로 이 processor도 페이지 단위 실행 결과({@link ConsistencyCheck.CheckOutcome})를
 * 그대로 반환할 뿐, 누적은 하지 않는다. 여러 페이지에 걸친 결과 누적은
 * {@link CheckResultAccumulatorWriter}가 담당한다.
 */
@RequiredArgsConstructor
public class ConsistencyCheckItemProcessor implements ItemProcessor<List<Long>, ConsistencyCheck.CheckOutcome>, StepExecutionListener {

    private final ConsistencyCheck check;
    private final LocalDateTime to;
    private final int stepIndex;
    private final int totalSteps;
    private final long totalEventCount;
    private final ApplicationEventPublisher eventPublisher;

    private long jobExecutionId;
    private long processedEventCount;
    private long violationCount;

    @Override
    public void beforeStep(StepExecution stepExecution) {
        jobExecutionId = stepExecution.getJobExecution().getId();
        processedEventCount = 0;
        violationCount = 0;
    }

    @Override
    public ConsistencyCheck.CheckOutcome process(List<Long> eventIds) {
        Scope scope = Scope.all(eventIds, to);
        ConsistencyCheck.CheckOutcome outcome = check.check(scope);
        processedEventCount += eventIds.size();
        violationCount += outcome.getViolationCount();
        eventPublisher.publishEvent(ConsistencyStepProgressEvent.builder()
                .jobExecutionId(jobExecutionId)
                .checkName(check.getName())
                .checkLabel(check.getLabel())
                .stepIndex(stepIndex)
                .totalSteps(totalSteps)
                .eventIds(List.copyOf(eventIds))
                .processedEventCount(processedEventCount)
                .totalEventCount(totalEventCount)
                .violationCount(violationCount)
                .build());
        return outcome;
    }
}
