package com.ace.consistency.batch;

import org.springframework.batch.core.configuration.support.MapJobRegistry;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.support.TaskExecutorJobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/**
 * 정합성 검증 배치 Job을 비동기로 실행하기 위한 {@link JobOperator} 설정.
 * 기본 {@code JobOperator}는 호출한 스레드에서 Job을 동기적으로 실행하지만, ALL 스코프
 * 검증은 전체 데이터를 스캔하므로 API 응답을 막지 않도록 별도 스레드에서 실행해야 한다.
 * 이를 위해 {@link TaskExecutorJobOperator}에 {@link SimpleAsyncTaskExecutor}를 지정해,
 * Job 시작(start) 즉시 별도 스레드에서 Step들이 진행되고 호출부는 {@code JobExecution}만
 * 받아 바로 리턴받도록 한다.
 */
@Configuration
public class BatchAsyncConfig {

    /**
     * Job을 {@code "consistency-batch-"} 이름의 새 스레드에서 실행하는 비동기 {@link JobOperator}.
     * {@link MapJobRegistry}는 실행 중인 Job을 이름으로 조회/중단(stop)할 수 있도록 등록해두는 레지스트리다.
     */
    @Bean
    public JobOperator asyncJobOperator(JobRepository jobRepository) {
        TaskExecutorJobOperator jobOperator = new TaskExecutorJobOperator();
        jobOperator.setJobRepository(jobRepository);
        jobOperator.setJobRegistry(new MapJobRegistry());
        jobOperator.setTaskExecutor(new SimpleAsyncTaskExecutor("consistency-batch-"));
        return jobOperator;
    }
}
