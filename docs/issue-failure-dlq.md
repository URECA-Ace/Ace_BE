# 발급 실패(DLQ) 관제 구현 기록

이슈: `[Feat] 발급 실패(DLQ) 관제 API — 조회 · 재시도 · 종결` (#78)
브랜치: `feat/78-dlq-api`

`issue_failure_log` 는 이전부터 적재되고 있었으나 조회 수단이 없어 DB 직접 조회 외에는 볼 방법이 없었다.
이 작업은 조회·조치 API 를 붙이고, 흩어져 있던 판정 기준을 한 곳으로 모은 것이다.

---

## 왜 `resolved_at` 으로 판정하지 않는가

보상에 성공해도 `resolvedAt` 은 채워지지 않는다. `IssuePersistenceCoordinator` 는 원복 성공 시 기록만 남기고 해소로 표시하지 않는다.
실패 주입 실측(2026-08-29)에서 생성된 8건 전부 `resolved_at IS NULL` 이었고, 그중 6건은 재고가 이미 돌아온 종결 건이었다.

`resolved_at IS NULL` 을 미해소로 쓰면 종결된 건이 화면에서 영원히 사라지지 않는다.
그래서 판정은 `compensation_result` 기준으로 한다.

| 상태 | 조건 |
|---|---|
| `SETTLED` | `resolvedAt` 이 있거나, `compensationResult` 가 그룹의 settled 집합에 속함 |
| `RETRYABLE` | 미해소이고 그룹의 retryable 집합에 속함 |
| `UNRECOVERABLE` | 미해소이고 둘 다 아님 (`NULL` 포함) |

## 왜 그룹으로 나누는가

`ConfirmFailureRetryService` 와 `CompensationFailureRetryService` 는 같은 이름의 판정 값을 서로 다른 의미로 쓴다.
`INTERNAL_WRITE_ERROR` 는 확정 결과와 보상 결과 양쪽에 있고, `SKIPPED_PERSISTED` 는 저장 그룹에만 있다.
합산하면 틀린 수가 나오므로 `IssueFailureStageGroup` 으로 나눴다.

| 그룹 | 단계 | 담당 재처리기 |
|---|---|---|
| `PERSIST` | `DB_INSERT`, `RELAY`, `COMPENSATE` | `CompensationFailureRetryService` |
| `CONFIRM` | `CONFIRM` | `ConfirmFailureRetryService` |

판정 집합은 이제 `IssueFailureStageGroup` 이 유일한 출처다. 두 재처리기의 `STAGES` / `RETRYABLE_RESULTS` / `SETTLED_RESULTS` 상수도 여기서 가져오도록 바꿨다.
전에는 같은 값이 두 클래스에 복사돼 있어 한쪽만 고치면 조용히 어긋났다.

## 액션 규칙

```
SETTLED                          → 없음
RETRYABLE                        → RETRY
UNRECOVERABLE, attemptCount = 0  → RETRY
UNRECOVERABLE, attemptCount ≥ 1  → RETRY, RESOLVE
```

계획 단계에서는 `UNRECOVERABLE + attemptCount = 0` 을 액션 없음으로 뒀으나, 그러면 `RESOLVE` 의 전제인 "한 번은 시도해 봤다" 를 만들 방법이 사라져 `RETRY` 를 허용하도록 바꿨다.
자동 재처리기는 `UNRECOVERABLE` 을 집지 않으므로, 이 건에 대한 수동 재시도가 유일한 재시도 경로이기도 하다.

`RETRY` 는 두 재처리기의 `retry(IssueFailureLog)` 를 그대로 호출한다. 복구 절차를 관제 쪽에서 다시 만들지 않는다.

## RESOLVE 안전장치

`IssueFailureLog.resolve()` 는 실제 복구를 검증하지 않고 시각만 찍는다. 그대로 노출하면 재고 손실을 감추는 버튼이 된다.
세 가지를 걸었다.

1. `attemptCount >= 1` — 재시도해 보지 않은 건은 종결 불가
2. `reason` 필수, `operator` 기록
3. 종결 직전 `IssuePersistenceProbe.probe()` 재실행 → 결과(`PERSISTED` / `ABSENT` / `UNVERIFIED`)를 `resolve_probe_result` 에 저장

사람이 닫되, 닫던 순간의 저장 상태가 함께 남는다. 나중에 정합성 검증이 뭘 잡아도 대조할 수 있다.

## 스키마 변경

`issue_failure_log` 에 3개 컬럼 추가 (모두 nullable, 수동 종결 건에만 채워짐).

| 컬럼 | 타입 |
|---|---|
| `resolved_by` | `varchar(60)` |
| `resolve_reason` | `varchar(300)` |
| `resolve_probe_result` | `varchar(20)` |

## 조회 쿼리

상태마다 조건이 달라 `findSettled` / `findRetryable` / `findUnrecoverable` 로 나눴다.
필터는 `eventId`, `stage`, `status`, `requestId` 네 개로 고정했다. 자유 조합을 허용하면 300만 건에서 인덱스를 타지 못한다.

사용하는 인덱스:
- `(event_id, failure_stage)` — 회차·단계 필터
- `(failure_stage, compensation_result, resolved_at, last_attempt_at)` — 상태 필터 + 정렬
- `(request_id)` — 요청 ID 단독 조회

정렬은 `last_attempt_at ASC, failure_id ASC`. 자동 재처리기와 같은 순서라 화면이 스케줄러 관점과 일치한다.

## 실패 데이터 만드는 법

존재하지 않는 `userId` 로 발급을 요청하면 `coupon_issue.user_id` 의 FK 제약에 걸린다.
Redis 판정은 사용자 존재를 모르므로 통과하고 DB 에서만 터진다. 재고는 자동으로 원복되어 안전하다.

```bash
curl -X POST "http://localhost:8080/api/v1/events/{eventId}/issues" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"userId": 90000001}'
```

- `mode=RELAY` → `stage=RELAY` / `mode=SYNC` → `stage=DB_INSERT`
- `CONFIRM` 은 SYNC 로 발급한 뒤 `DEL coupon:{campaign:<id>}:requests` 하고 RELAY 로 재기동하면 재현된다
- `COMPENSATE` 는 보상 호출 자체가 예외로 끝나야 나온다. DB 실패와 Redis 장애가 겹치는 순간이라 코드 수정 없이는 재현 불가

**MySQL 을 내리는 방식은 안 된다.** 실패 기록도 MySQL 에 쓰므로 같이 실패하고
`JpaIssueFailureRecorder` 가 로그만 남기고 삼킨다. 화면이 빈 채로 나온다.

## 실측 결과 (2026-08-29)

| 주입 | mode | stage | compensation_result | 상태 |
|---|---|---|---|---|
| 없는 userId | RELAY | `RELAY` | `COMPENSATED` | SETTLED |
| 없는 userId | SYNC | `DB_INSERT` | `COMPENSATED` | SETTLED |
| requests 삭제 후 릴레이 소비 | RELAY | `CONFIRM` | `REQUEST_NOT_FOUND` | UNRECOVERABLE |

`REQUEST_NOT_FOUND` 는 두 집합 어디에도 없어 `UNRECOVERABLE` 로 잡힌다.
재고 자체는 멀쩡한데 `blockedEventIds` 에 회차가 계속 올라오는 상태라, `RESOLVE` 가 실제로 필요한 경우가 이 케이스다.

발급 6건 / 잔여 4994 / 총 재고 5000 — 초과 발급 0, 재고 누수 0 으로 마감됐다.
