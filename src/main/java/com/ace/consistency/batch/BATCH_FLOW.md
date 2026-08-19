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

## 재시작(restart)과 관련해 알아둘 점

`EventIdPageReader`와 `CheckResultAccumulatorWriter`는 `ItemStream`을 구현해 각각
"마지막으로 읽은 event_id"와 "지금까지 누적된 위반 건수/샘플"을 `ExecutionContext`에
저장한다. Step이 실패해 같은 `JobParameters`로 재시작되면, Spring Batch가 이 상태를
복원해 처음부터 다시 읽지 않고 중단된 지점부터 이어간다. 단, `ConsistencyBatchJobFactory`는
매 호출 시 새 `runId`(`JobParameters`)로 Job을 만들기 때문에, 재시작 시나리오는 같은
`JobExecution`을 대상으로 명시적으로 재실행할 때만 의미가 있다.
