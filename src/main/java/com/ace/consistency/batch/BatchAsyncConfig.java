package com.ace.consistency.batch;

import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.configuration.support.MapJobRegistry;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.support.TaskExecutorJobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JdbcJobRepositoryFactoryBean;
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.boot.sql.init.DatabaseInitializationSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;

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
     * 배치 메타데이터 테이블(BATCH_JOB_INSTANCE 등)을 spring-batch-core에 내장된
     * schema-mysql.sql로 앱 기동 시 자동 생성한다. 이미 테이블이 있는 DB(재기동, 다른 개발자
     * 환경 등)에서는 각 CREATE TABLE 구문이 "이미 존재함" 에러를 내는데, continueOnError를
     * 켜서 그 구문만 건너뛰고 넘어가도록 한다(스키마가 IF NOT EXISTS를 쓰지 않기 때문).
     */
    @Bean
    public DataSourceScriptDatabaseInitializer batchSchemaInitializer(DataSource dataSource) {
        DatabaseInitializationSettings settings = new DatabaseInitializationSettings();
        settings.setSchemaLocations(List.of("classpath:org/springframework/batch/core/schema-mysql.sql"));
        settings.setMode(DatabaseInitializationMode.ALWAYS);
        settings.setContinueOnError(true);
        return new DataSourceScriptDatabaseInitializer(dataSource, settings);
    }

    /**
     * JobRepository를 명시적으로 빈 등록하지 않으면 Spring Boot 4.1의 배치 자동 설정이
     * {@code ResourcelessJobRepository}로 폴백하는데, 이는 JobInstance/JobExecution id를
     * 항상 1로 고정하는 메모리 스텁이라 재시작(restart)이 동작할 수 없다. 앱의 MySQL
     * DataSource를 그대로 사용해 실제 배치 메타데이터 테이블(BATCH_JOB_INSTANCE 등)에
     * 기록하도록 JDBC 기반 JobRepository를 직접 구성한다.
     * batchSchemaInitializer가 먼저 실행되어 테이블이 준비된 뒤에 만들어져야 한다.
     */
    @Bean
    @DependsOn("batchSchemaInitializer")
    public JobRepository jobRepository(DataSource dataSource, PlatformTransactionManager transactionManager) throws Exception {
        JdbcJobRepositoryFactoryBean factoryBean = new JdbcJobRepositoryFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setTransactionManager(transactionManager);
        factoryBean.afterPropertiesSet();
        return factoryBean.getObject();
    }

    /**
     * 실행 중인 Job을 이름으로 조회/중단(stop)하거나, 실패한 Job을 재시작(restart)할 때
     * 그 이름에 해당하는 {@link org.springframework.batch.core.job.Job}을 찾을 수 있도록
     * 등록해두는 레지스트리. {@code consistencyVerificationJob}은 고정 Job 빈이 아니라
     * 요청마다 새로 조립되므로, 재시작 직전에 {@link com.ace.consistency.common.ConsistencyVerificationRunner}가
     * 직접 등록을 갱신한다.
     */
    @Bean
    public JobRegistry jobRegistry() {
        return new MapJobRegistry();
    }

    /**
     * Job을 {@code "consistency-batch-"} 이름의 새 스레드에서 실행하는 비동기 {@link JobOperator}.
     */
    @Bean
    public JobOperator asyncJobOperator(JobRepository jobRepository, JobRegistry jobRegistry) {
        TaskExecutorJobOperator jobOperator = new TaskExecutorJobOperator();
        jobOperator.setJobRepository(jobRepository);
        jobOperator.setJobRegistry(jobRegistry);
        jobOperator.setTaskExecutor(new SimpleAsyncTaskExecutor("consistency-batch-"));
        return jobOperator;
    }
}
