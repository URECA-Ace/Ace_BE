package com.ace.consistency.batch;

import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResultPersister;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.builder.SimpleJobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

/**
 * {@code ALL} 스코프 정합성 검증을 위한 Spring Batch {@link Job}을 동적으로 조립하는 팩토리.
 * 실행 시점에 넘어온 {@link ConsistencyCheck} 목록 중 ALL 스코프를 지원하는 것만 걸러내어,
 * Check 하나당 Step 하나(reader: {@link EventIdPageReader}, processor:
 * {@link ConsistencyCheckItemProcessor}, writer: {@link CheckResultAccumulatorWriter})를 만들고,
 * 이 Step들을 순차 실행되는 하나의 Job으로 묶는다.
 *
 * 미리 정의된 고정 Job이 아니라 요청마다 Check 구성이 바뀔 수 있어 매 호출 시 새로
 * 빌드한다. 실제 실행은 {@code ConsistencyVerificationRunner.runAsync()}가 이 Job을
 * {@code JobOperator}에 넘기는 방식으로 이루어진다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConsistencyBatchJobFactory {

    private static final String JOB_NAME = "consistencyVerificationJob";
    private static final int PAGE_SIZE = 500;

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final VerificationResultPersister resultPersister;
    private final BatchFailureLogRepository failureLogRepository;

    public Job buildJob(List<ConsistencyCheck> checks, Scope scope, TriggerType triggerType) {
        List<Step> steps = checks.stream()
                .filter(check -> supportsAll(check))
                .map(check -> buildStep(check, scope, triggerType))
                .toList();

        if (steps.isEmpty()) {
            throw new IllegalArgumentException("ALL 스코프를 지원하는 Check가 하나도 없습니다.");
        }

        ConsistencyJobExecutionListener jobExecutionListener =
                new ConsistencyJobExecutionListener(failureLogRepository, jobRepository, checks, scope.getTo(), triggerType);

        // 첫 번째 step 등록 후 차례차례 simpleJobBuilder.next(steps.get(i))로 다음  step을 붙인다.
        // 이렇게 하면 한 step이 오류로 실패하면 그 다음 step들부터는 실행을 안한다.
        JobBuilder jobBuilder = new JobBuilder(JOB_NAME, jobRepository)
                .listener(jobExecutionListener);
        SimpleJobBuilder simpleJobBuilder = jobBuilder.start(steps.getFirst());
        for (int i = 1; i < steps.size(); i++) {
            simpleJobBuilder = simpleJobBuilder.next(steps.get(i));
        }
        return simpleJobBuilder.build();
    }

    private boolean supportsAll(ConsistencyCheck check) {
        if (check.supportedScopeTypes().contains(Scope.ScopeType.ALL)) {
            return true;
        }
        log.warn("Check {} does not support ALL scope, skipping in batch job.", check.getName());
        return false;
    }

    private Step buildStep(ConsistencyCheck check, Scope scope, TriggerType triggerType) {
        EventIdPageReader reader = new EventIdPageReader(jdbcTemplate, PAGE_SIZE, scope.getTo());
        ConsistencyCheckItemProcessor processor = new ConsistencyCheckItemProcessor(check, scope.getTo());
        CheckResultAccumulatorWriter writer = new CheckResultAccumulatorWriter();
        ConsistencyStepCompletionListener listener =
                new ConsistencyStepCompletionListener(check, writer, scope, triggerType, resultPersister);

        return new StepBuilder(check.getName() + "Step", jobRepository)
                .<List<Long>, ConsistencyCheck.CheckOutcome>chunk(1)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .transactionManager(transactionManager)
                .listener(listener)
                .build();
    }
}
