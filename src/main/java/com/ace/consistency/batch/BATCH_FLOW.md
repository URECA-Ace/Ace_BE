# consistency 배치(ALL 스코프) 동작 흐름

이 문서는 `com.ace.consistency.batch` 패키지가 어떤 상황에서, 어떤 순서로 동작하는지 설명한다.

## 왜 배치가 필요한가

정합성 검증(`ConsistencyCheck`)은 `EVENT`/`AS_OF_RANGE`/`ALL` 세 가지 스코프로 실행될 수 있다.
`ALL` 스코프는 전체 이벤트 데이터를 대상으로 하므로 한 번에 메모리에 올려 처리하기 어렵고,
API 요청-응답 사이에서 동기로 끝내기도 어렵다. 그래서 `ALL` 스코프만 Spring Batch Job으로
분리해 event_id를 페이지 단위로 나눠 읽고, 별도 스레드에서 비동기로 실행한다.
`EVENT`/`AS_OF_RANGE` 스코프는 배치를 타지 않고 `ConsistencyVerificationRunner.run()`이
직접 동기 실행한다.

## 전체 흐름

```
ConsistencyVerificationRunner.runAsync(checks, scope(ALL), triggerType)
        │
        ▼
ConsistencyBatchJobFactory.buildJob(checks, scope, triggerType)
        │  - ALL 스코프를 지원하는 check만 필터링
        │  - check 하나당 Step 하나를 만들어 순차 실행되는 Job으로 조립
        ▼
asyncJobOperator.start(job, params)      (BatchAsyncConfig)
        │  - TaskExecutorJobOperator가 SimpleAsyncTaskExecutor 위에서 Job을 실행
        │  - 호출부는 JobExecution을 즉시 반환받고 대기하지 않음
        ▼
[Step: check마다 하나씩, 순차 실행]
        │
        ├─ EventIdPageReader.read()            → event_id를 pageSize만큼 페이징 조회
        ├─ ConsistencyCheckItemProcessor.process() → 해당 check를 이 페이지(Scope.ALL)로 실행
        ├─ CheckResultAccumulatorWriter.write()  → 페이지별 CheckOutcome을 Step 종료까지 누적
        │   (위 세 단계는 reader가 더 읽을 데이터가 없을 때까지 반복)
        │
        ▼
ConsistencyStepCompletionListener.afterStep()
        │  - writer에 누적된 결과 + StepExecution 상태(성공/실패)로 VerificationResult 생성
        │  - VerificationResultPersister.saveAndNotify()로 즉시 DB 저장
        ▼
(다음 Step으로 이동, 모든 Step 종료 시)
        ▼
ConsistencyJobExecutionListener.afterJob()
        │  - Job 전체의 최종 상태(COMPLETED/FAILED)를 로깅
```

## 클래스별 역할 한눈에 보기

| 분류 | 클래스 | 역할 |
|---|---|---|
| Job/Step 조립 | `ConsistencyBatchJobFactory` | Check 목록을 받아 Step들로 나누고 하나의 Job으로 조립 |
| 비동기 실행 설정 | `BatchAsyncConfig` | Job을 별도 스레드에서 실행하는 `JobOperator` 빈 등록 |
| Reader | `EventIdPageReader` | event_id를 페이지 단위로 순회 조회, 재시작 대비 마지막 위치 저장 |
| Processor | `ConsistencyCheckItemProcessor` | 한 페이지의 event_id로 해당 Step의 Check 실행 |
| Writer | `CheckResultAccumulatorWriter` | 여러 페이지의 Check 결과(위반 건수, 샘플)를 Step 종료까지 누적 |
| Step 리스너 | `ConsistencyStepCompletionListener` | Step 종료 시 누적 결과를 `VerificationResult`로 변환해 저장 |
| Job 리스너 | `ConsistencyJobExecutionListener` | Job 전체 종료 시 최종 상태 로깅 |

## DB에 생성되는 테이블

`BatchAsyncConfig.batchSchemaInitializer`가 앱 기동 시 `spring-batch-core`에 내장된
`schema-mysql.sql`을 실행해 아래 9개 메타데이터 테이블을 자동 생성한다. Job/Step 실행
이력을 프레임워크가 스스로 추적하기 위한 테이블로, 우리 도메인 데이터가 아니다.

**BATCH_JOB_INSTANCE** — Job 이름 + JobParameters(식별용 파라미터만)로 구분되는 "논리적
실행 단위". 같은 JobInstance를 다른 JobParameters로 다시 실행할 수는 없고, 실패 시
재시작하면 같은 JobInstance 아래 새 JobExecution이 추가된다.
| 컬럼 | 의미 |
|---|---|
| JOB_INSTANCE_ID | PK |
| VERSION | 낙관적 락 버전 |
| JOB_NAME | Job 이름 |
| JOB_KEY | JobParameters 중 identifying 파라미터들을 직렬화/해시한 값. JOB_NAME과 묶여 유니크 |

**BATCH_JOB_EXECUTION** — JobInstance를 한 번 실행한 기록 한 건(재시작하면 row가 새로
추가됨). `ConsistencyJobExecutionListener.afterJob()`이 참조하는 `jobExecution.getId()`가
이 테이블의 PK다.
| 컬럼 | 의미 |
|---|---|
| JOB_EXECUTION_ID | PK |
| VERSION | 낙관적 락 버전 |
| JOB_INSTANCE_ID | 소속 BATCH_JOB_INSTANCE FK |
| CREATE_TIME | JobExecution row 생성 시각 |
| START_TIME | Job 실행 시작 시각 |
| END_TIME | Job 실행 종료 시각 |
| STATUS | BatchStatus (COMPLETED/FAILED/STOPPED 등) |
| EXIT_CODE / EXIT_MESSAGE | Job 종료 코드와 메시지(실패 시 예외 스택 일부 포함) |
| LAST_UPDATED | row 마지막 갱신 시각 |

**BATCH_JOB_EXECUTION_PARAMS** — 해당 JobExecution을 실행할 때 넘긴 JobParameters
(`ConsistencyBatchJobFactory`가 만드는 `runId` 등)를 파라미터 하나당 한 row로 저장한다.
| 컬럼 | 의미 |
|---|---|
| JOB_EXECUTION_ID | 소속 BATCH_JOB_EXECUTION FK |
| PARAMETER_NAME | 파라미터 이름(예: `runId`) |
| PARAMETER_TYPE | 파라미터 타입(java 클래스 FQCN) |
| PARAMETER_VALUE | 파라미터 값(문자열로 직렬화) |
| IDENTIFYING | 이 파라미터가 JOB_KEY(JobInstance 식별)에 포함되는지 여부(Y/N) |

**BATCH_JOB_EXECUTION_CONTEXT** — Job 레벨 `ExecutionContext` 저장소. JobExecution당
1 row. `ConsistencyVerificationRunner.persistRestartContext()`가 저장하는
checks/scope/triggerType이 여기 들어간다.
| 컬럼 | 의미 |
|---|---|
| JOB_EXECUTION_ID | PK이자 소속 BATCH_JOB_EXECUTION FK |
| SHORT_CONTEXT | ExecutionContext를 JSON 직렬화한 값(짧으면 전체, 길면 잘린 앞부분) |
| SERIALIZED_CONTEXT | SHORT_CONTEXT가 잘렸을 때 전체 내용이 들어가는 TEXT 컬럼 |

**BATCH_STEP_EXECUTION** — Step(Check 하나) 실행 기록 한 건. `ConsistencyStepCompletionListener`가
성공/실패 판단에 쓰는 STATUS, 청크 처리 통계(READ/WRITE/SKIP/ROLLBACK COUNT)가 여기 쌓인다.
| 컬럼 | 의미 |
|---|---|
| STEP_EXECUTION_ID | PK |
| VERSION | 낙관적 락 버전 |
| STEP_NAME | Step 이름(Check 이름) |
| JOB_EXECUTION_ID | 소속 BATCH_JOB_EXECUTION FK |
| CREATE_TIME / START_TIME / END_TIME | Step row 생성/시작/종료 시각 |
| STATUS | BatchStatus |
| COMMIT_COUNT | 트랜잭션 커밋 횟수(청크 수) |
| READ_COUNT | EventIdPageReader가 읽은 item 수 |
| FILTER_COUNT | Processor가 걸러낸(null 반환) item 수 |
| WRITE_COUNT | Writer가 처리한 item 수 |
| READ_SKIP_COUNT / WRITE_SKIP_COUNT / PROCESS_SKIP_COUNT | 각 단계에서 스킵된 item 수 |
| ROLLBACK_COUNT | 롤백된 트랜잭션 수 |
| EXIT_CODE / EXIT_MESSAGE | Step 종료 코드와 메시지 |
| LAST_UPDATED | row 마지막 갱신 시각 |

**BATCH_STEP_EXECUTION_CONTEXT** — Step 레벨 `ExecutionContext` 저장소. StepExecution당
1 row. `EventIdPageReader`가 저장하는 "마지막으로 읽은 event_id"와 `CheckResultAccumulatorWriter`가
저장하는 "지금까지 누적된 위반 건수/샘플"이 여기 들어가며, 재시작 시 이 값으로 복원된다.
| 컬럼 | 의미 |
|---|---|
| STEP_EXECUTION_ID | PK이자 소속 BATCH_STEP_EXECUTION FK |
| SHORT_CONTEXT | ExecutionContext를 JSON 직렬화한 값 |
| SERIALIZED_CONTEXT | SHORT_CONTEXT가 잘렸을 때 전체 내용이 들어가는 TEXT 컬럼 |

**BATCH_JOB_INSTANCE_SEQ / BATCH_JOB_EXECUTION_SEQ / BATCH_STEP_EXECUTION_SEQ** — MySQL은
네이티브 시퀀스가 없어서, 각각 JOB_INSTANCE_ID/JOB_EXECUTION_ID/STEP_EXECUTION_ID를
채번하기 위해 Spring Batch가 별도 테이블로 시퀀스를 흉내낸다. `UNIQUE_KEY` 컬럼에 항상
`'0'` 하나뿐인 row가 있고, 채번할 때마다 이 row의 `ID`를 락 걸고 증가시킨 뒤 반환한다.
세 테이블 모두 row가 정확히 1개 있어야 정상 동작하므로, 데이터를 비울 때 TRUNCATE만 하면
안 되고 seed row(`ID=0, UNIQUE_KEY='0'`)를 다시 넣어줘야 한다.
| 컬럼 | 의미 |
|---|---|
| ID | 마지막으로 채번된 값(다음 채번 시 +1) |
| UNIQUE_KEY | 항상 `'0'` 하나뿐인 값(row를 1개로 강제하는 유니크 제약) |

이 9개와 별개로, Job이 COMPLETED가 아닌 상태로 끝나면 `ConsistencyJobExecutionListener`가
직접 `batch_failure_log`(JPA `ddl-auto=update`로 생성, `BatchFailureLogEntity` 매핑)에도
한 row를 남긴다. Spring Batch 표준 테이블이 아니라 이 프로젝트에서 추가한 도메인 테이블이다.
| 컬럼 | 의미 |
|---|---|
| id | PK |
| job_execution_id | 실패한 BATCH_JOB_EXECUTION.JOB_EXECUTION_ID |
| job_instance_id | 실패한 BATCH_JOB_INSTANCE.JOB_INSTANCE_ID |
| status | Job 최종 BatchStatus(FAILED/STOPPED 등) |
| failed_step_name | 실패한 Step 이름. Step 시작 전 Job 자체가 실패했으면 null |
| error_message | 원인 예외를 `"{ExceptionSimpleName}: {message}"` 형태로 요약 |
| occurred_at | 이 row가 기록된 시각 |

## 재시작(restart)과 관련해 알아둘 점

`EventIdPageReader`와 `CheckResultAccumulatorWriter`는 `ItemStream`을 구현해 각각
"마지막으로 읽은 event_id"와 "지금까지 누적된 위반 건수/샘플"을 `ExecutionContext`에
저장한다. Step이 실패해 같은 `JobParameters`로 재시작되면, Spring Batch가 이 상태를
복원해 처음부터 다시 읽지 않고 중단된 지점부터 이어간다. 단, `ConsistencyBatchJobFactory`는
매 호출 시 새 `runId`(`JobParameters`)로 Job을 만들기 때문에, 재시작 시나리오는 같은
`JobExecution`을 대상으로 명시적으로 재실행할 때만 의미가 있다.
