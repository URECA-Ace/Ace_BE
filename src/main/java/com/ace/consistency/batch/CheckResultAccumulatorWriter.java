package com.ace.consistency.batch;

import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.entity.VerificationViolationEntity;
import com.ace.consistency.repository.VerificationViolationRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStream;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemWriter;

/**
 * {@link ConsistencyCheckItemProcessor}가 페이지마다 만들어내는 {@link ConsistencyCheck.CheckOutcome}을
 * Step이 끝날 때까지 누적하는 {@link ItemWriter}. 실패 건수(violationCount)는 재시작을 위해
 * {@link ExecutionContext}에 저장하고, 위반 건은 각각 {@link VerificationViolationEntity}로 즉시
 * 저장한다(청크마다 커밋). 이 시점에는 아직 verification_result가 만들어지지 않았으므로
 * 재시작 전후에도 유지되는 JobInstance/Step 조합으로 임시 태깅해두고, Step이 최종 완료되면
 * {@link ConsistencyStepCompletionListener}가 실제 verification_result와 연결한다.
 *
 * {@link ItemStream}을 구현해 violationCount를 {@link ExecutionContext}에 저장하므로,
 * Step이 재시작되어도 이전에 처리한 페이지들의 집계가 유지된다.
 */
@RequiredArgsConstructor
public class CheckResultAccumulatorWriter
        implements ItemWriter<ConsistencyCheck.CheckOutcome>, ItemStream, StepExecutionListener {

    private static final String VIOLATION_COUNT_KEY = "checkResultAccumulatorWriter.violationCount";

    private final VerificationViolationRepository violationRepository;

	@Getter
	private int violationCount;
	private Long jobInstanceId;
	private String stepName;

    @Override
	public void beforeStep(StepExecution stepExecution) {
		this.jobInstanceId = stepExecution.getJobExecution().getJobInstance().getInstanceId();
		this.stepName = stepExecution.getStepName();
    }

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        violationCount = executionContext.getInt(VIOLATION_COUNT_KEY, 0);
    }

    @Override
    public void write(Chunk<? extends ConsistencyCheck.CheckOutcome> chunk) {
        for (ConsistencyCheck.CheckOutcome outcome : chunk) {
            if (outcome.isPass()) {
                continue;
            }
            violationCount += outcome.getViolationCount();
            if (!outcome.getViolations().isEmpty()) {
                violationRepository.saveAll(
                        outcome.getViolations().stream()
								.map(violation -> VerificationViolationEntity.forBatchStep(jobInstanceId, stepName, violation))
                                .toList()
                );
            }
        }
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        executionContext.putInt(VIOLATION_COUNT_KEY, violationCount);
    }

    public boolean isPass() {
        return violationCount == 0;
    }
}
