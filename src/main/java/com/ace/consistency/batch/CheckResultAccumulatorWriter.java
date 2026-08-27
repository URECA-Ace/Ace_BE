package com.ace.consistency.batch;

import com.ace.consistency.common.ConsistencyCheck;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStream;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link ConsistencyCheckItemProcessor}가 페이지마다 만들어내는 {@link ConsistencyCheck.CheckOutcome}을
 * Step이 끝날 때까지 누적하는 {@link ItemWriter}. DB에 쓰지 않고, 실패 건수(violationCount)와
 * 샘플 데이터 전체를 메모리에 모아둔다. Step 종료 후
 * {@link ConsistencyStepCompletionListener}가 이 writer의 누적 결과를 읽어 최종
 * VerificationResult를 만든다.
 *
 * {@link ItemStream}을 구현해 누적값을 {@link ExecutionContext}에 저장하므로,
 * Step이 재시작되어도 이전에 처리한 페이지들의 집계가 유지된다.
 */
public class CheckResultAccumulatorWriter implements ItemWriter<ConsistencyCheck.CheckOutcome>, ItemStream {

    private static final String VIOLATION_COUNT_KEY = "checkResultAccumulatorWriter.violationCount";
    private static final String SAMPLES_KEY = "checkResultAccumulatorWriter.samples";

    private int violationCount;
    private List<Map<String, Object>> samples;

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        violationCount = executionContext.getInt(VIOLATION_COUNT_KEY, 0);
        samples = executionContext.containsKey(SAMPLES_KEY)
                ? new ArrayList<>(executionContext.get(SAMPLES_KEY, List.class))
                : new ArrayList<>();
    }

    @Override
    public void write(Chunk<? extends ConsistencyCheck.CheckOutcome> chunk) {
        for (ConsistencyCheck.CheckOutcome outcome : chunk) {
            if (outcome.isPass()) {
                continue;
            }
            violationCount += outcome.getViolationCount();
            addSamples(outcome);
        }
    }

    @SuppressWarnings("unchecked")
    private void addSamples(ConsistencyCheck.CheckOutcome outcome) {
        List<Map<String, Object>> pageSamples = (List<Map<String, Object>>) outcome.getDiffDetail().get("sample");
        if (pageSamples == null) {
            return;
        }
        samples.addAll(pageSamples);
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        executionContext.putInt(VIOLATION_COUNT_KEY, violationCount);
        executionContext.put(SAMPLES_KEY, samples);
    }

    public boolean isPass() {
        return violationCount == 0;
    }

    public int getViolationCount() {
        return violationCount;
    }

    public List<Map<String, Object>> getSamples() {
        return samples;
    }
}
