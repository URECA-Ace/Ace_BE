# 대규모 트래픽 선착순 쿠폰 발급 시스템 - Ace_BE

> U+ 프리덤 데이, 매일 정오 한정 데이터 이용권 선착순 발급
> 교육 목적의 가상 시나리오이며 실제 서비스와 무관합니다.

이 문서는 **무엇을 어디까지 만들었는지**를 표로 대조합니다.
모든 ✅ 항목에는 그것을 증명하는 **테스트 클래스, Gradle 태스크, 파일 경로**를 함께 적었습니다.

| 기호 | 의미 |
|---|---|
| ✅ | 구현 완료 + 재현 가능한 증거 있음 |
| 🟡 | 부분 구현 (일부 경로/조건만 충족) |
| ❌ | 미착수 |

---

## 1. 캠페인 사양과 데이터 규모

**1회차당 쿠폰 1종류**를 발급하고, 이를 **300회차** 운영합니다.
따라서 이 시스템을 거쳐 나가는 쿠폰은 **총 300종류**입니다.

| 항목 | 사양 | 현재 더미 데이터 |
|---|---|---|
| 회차당 쿠폰 종류 | **1종류** | 1종류 |
| 회차 | **300회차** | 30회차 |
| 회차당 재고 | **10,000장** | 100,000장 |
| 나가는 쿠폰 종류 총합 | **300종류** (1종류 × 300회차) | 1종류 |
| 총 발급 이력 | **3,000,000건** | 3,000,000건 |
| 회원 | 1,000,000명 | 1,000,000명 |
| 1인 제한 | 회차당 1매 | 동일 |
| 유효기간 | 발급 시점 + 24시간 | 동일 |

```
300회차 × 10,000장 = 3,000,000건 ← 정합성 검증 대상 전량
쿠폰 종류      = 1종류 × 300회차 = 300종류
```

> 현재 더미는 `쿠폰 1종류 / 30회차 / 회차당 100,000장`으로 총량만 3,000,000건을 맞춘 구성입니다
> (`manual/CREATE_DUMP_DATA.md`). 회차 수가 30이라 실제로는 1종류만 나간 셈이 되므로,
> **회차를 300으로 늘려 300종류가 나가도록 재생성**하는 것이 남은 일에 포함되어 있습니다.

---

## 2. 아키텍처 (현재 구현 기준)

```
Client
  │  POST /api/v1/events/{eventId}/issues   (Idempotency-Key 필수)
  ▼
Spring Boot 3.x / Java 21
  │  EVALSHA
  ▼
Redis 7+ / coupon-issue.lua              ← 단일 원자 판정
  ├ 오픈/마감 시각 검증 (redis.call('TIME'))
  ├ requestId 멱등 검사 (Hash)
  ├ 중복 발급 검사 (Bitmap GETBIT)
  ├ 재고 차감 (DECR)
  ├ 순번 채번 (INCR)
  ├ 발급자 기록 (SETBIT)
  └ XADD issue-stream                      ← Outbox. Lua 안이라 원자적
  │
  ├─ mode=SYNC  → 같은 요청 스레드에서 MySQL 저장 후 202
  └─ mode=RELAY → 202 즉시 응답, Stream Consumer 가 MySQL 저장
                                           ▼
                               MySQL 8.4 (UNIQUE 4종 2차 방어)
                                           ▼
                               Spring Batch 정합성 검증 (6종 검사)
```

| 계층 | 현재 상태 |
|---|---|
| Redis Lua 원자 판정 | ✅ `src/main/resources/scripts/coupon-issue.lua` |
| 보상(원복) Lua | ✅ `coupon-issue-compensate.lua` |
| 캠페인 초기화 Lua | ✅ `coupon-campaign-initialize.lua` |
| 발급 현황 Lua | ✅ `coupon-event-stats.lua` |
| SYNC 저장 경로 | ✅ `coupon.issue.persistence.mode=SYNC` |
| RELAY 저장 경로 (Redis Stream Consumer) | ✅ `coupon.issue.persistence.mode=RELAY` |
| Kafka 파이프라인 | ❌ `docker-compose.yml` 에 브로커만 존재. **애플리케이션 코드 미연결** |
| Nginx / Prometheus / Grafana | ❌ 미구성 |

상세 규칙: [`docs/redis-coupon-issue.md`](docs/redis-coupon-issue.md)

---

## 3. 기능 요구사항 커버리지

| # | 요구 | 상태 | 구현 위치 | 증거 |
|---|---|:--:|---|---|
| F-1 | 쿠폰 발급 요청 진입점 | ✅ | `CouponIssueController:33` | `CouponIssueControllerTest` |
| F-2 | 재고 확인/차감 원자 처리 | ✅ | `coupon-issue.lua` (DECR) | `RedisCouponIssueIntegrationTest` |
| F-3 | 중복 발급 차단 (1인 1매) | ✅ | Bitmap + `uk_coupon_issue_event_user` | `CouponIssueConstraintTest` |
| F-4 | 발급 순번 채번 | ✅ | `coupon-issue.lua` (INCR) + `uk_coupon_issue_event_sequence` | `RedisCouponIssueIntegrationTest` |
| F-5 | 발급 확정 (영속화) | ✅ | `IssuePersistenceService`, `JdbcIssueWriter` | `SyncIssuanceAccuracyTest` / `RelayIssuanceAccuracyTest` |
| F-5b | 저장 실패 시 보상 | ✅ | `IssuePersistenceProbe`, `IssueFailureLog` | `IssuePersistenceProbeTest`, `IssuePersistenceCoordinatorTest` |
| F-6 | 캠페인 생성 + Redis 초기화 | ✅ | `CouponEventController:29` → `CampaignAdminService` | `CouponEventCreationServiceTest`, `CampaignAdminServiceTest` |
| F-7 | 오픈 시각 예약 | ✅ | `CouponEventOpenScheduler` | `CouponEventOpenSchedulerTest`, `CouponEventOpenServiceTest` |
| F-8 | 발급 현황 조회 | 🟡 | `CouponEventStatsController:26` | `CouponEventStatsServiceImplTest` - 잔여, 판정완료(`allocatedQuantity`)는 제공, **확정완료 수(MySQL COUNT)는 미제공** |
| F-9 | 부하 재현, 시연 | ✅ | k6, 별도 레포 `Ace_LT` | 20,000 VU 동시 출발 재현, 주 측정 28회차. Kafka 비교(S5)만 미실행 (§9) |
| - | 상태 전이 USED / CANCELED / EXPIRED | ❌ | `CouponIssueStatus` enum 만 존재 | 전이 코드 없음. 미머지 브랜치 `feat/25-coupon-usage-cancellation` |
| - | 쿠폰 종류 확대 | 🟡 | `coupon` 마스터 + `coupon_event.round` | 1회차당 1종류 구조는 이미 동작. **회차를 300으로 늘려 300종류를 내보내는 것은 더미 재생성 단계에서 처리** (§10) |
| - | 알림 발송 Mocking | 🟡 | - | PR #14 미머지 |
| - | 이벤트/로그 기반 정합성 검증 | 🟡 | - | PR #20 미머지 |

---

## 4. 비기능 요구사항 커버리지

| 축 | 요구 | 상태 | 실측 근거 |
|---|---|:--:|---|
| 동시성 | 재고 10,000 / 동시 20,000 → 초과 발급 0건 | ✅ | 20,000요청 / 10,000재고에서 **승인 정확히 10,000 / 초과 0**. 최종 재고, Bitmap bit 수, 순번, Stream 엔트리 수 모두 승인 수와 일치 (`docs/performance/redis-lua-performance.md`) |
| 동시성 | 1인 최대 1매 | ✅ | Bitmap 판정 + DB `uk_coupon_issue_event_user` 2중. `CouponIssueConstraintTest` |
| 멱등성 | 동일 요청 반복 → 1회만 반영 | ✅ | `Idempotency-Key`(UUID) 필수, Lua 가 requestId 결과 재반환. UNIQUE `request_id`, `message_id` |
| 성능 | 판정 구간 처리량 | ✅ | **6,864 req/s**, P50 17.2ms / P95 29.7ms / **P99 38.3ms** (Lua v2, Redis 전용 벤치마크) |
| 성능 | 판정 로직 강화 후에도 tail latency 개선 | ✅ | v1 대비 P95 −11.39% / P99 −11.16% / 최대 −28.09%, 처리량 +1.79% |
| 정합성 | 300만 건 전수 검증 수단 | ✅ | Spring Batch + 검사 6종. `Scope.ALL` 지원, `EventIdPageReader` 페이징 |
| 정합성 | 재고 오차 0 방어 | ✅ | Lua 원자 판정, 부분 쓰기 역순 롤백, DB UNIQUE 4종, 저장 실패 시 DB 재조회 후 보상 |
| 보안 | 개인정보 마스킹 | 🟡 | `MaskingUtil`(email / name / phone 정규식) 적용 지점 2곳: `ApiResponse.error()` 전체 에러 메시지, `issue_failure_log.error_message`. `GlobalExceptionHandler.sanitizedStackTrace()` 는 **예외 메시지를 아예 로그에 남기지 않고** 클래스명 프레임만 기록 후 `incidentId` 로 응답과 연결 (MySQL `Duplicate entry 'x@y.com'` 유출 차단). **남은 구멍: 다른 `log.error(..., ex)` 호출부는 원본 예외를 그대로 넘김, logback 마스킹 컨버터 없음** |
| 운영 | DB 커넥션 풀 크기 산정 | ✅ | `max_connections 151` 실측 → 관리, 배치 여유 20 제외 → 앱 2대로 역산 → **파드당 65** (`(151-20)/2`). 병목 관측용 10 과 함께 측정 (`src/main/resources/application-load.properties`) |
| 운영 | 지표 수집이 부하 경로에 영향 없을 것 | ✅ | actuator 를 `management.server.port=8081` 로 분리 |
| 재현성 | 같은 데이터, 같은 결과 | ✅ | Gradle 태스크 4종으로 동일 조건 재실행 (§6). 부하 측정도 결정론적 멱등키 + 3회 반복 중앙값으로 재현 (§9) |

---

## 5. API

| Method & Path | 용도 | 요청 | 응답 |
|---|---|---|---|
| `POST /api/v1/events/{eventId}/issues` | 선착순 발급 요청 | 헤더 `Idempotency-Key`(UUID) 필수 / 본문 `{ "userId": 1 }` | **202** `{eventId, userId, issueSequence, remainingStock, ...}` |
| `GET /api/v1/events/{eventId}/issues/{requestId}` | 발급 결과 조회 (RELAY 폴링용) | - | **200** `{eventId, userId, issueSequence, remainingStock, ...}` |
| `GET /api/v1/events/{eventId}/issuance-stats` | 발급 현황 실시간 조회 | - | **200** `{eventId, totalStock, allocatedQuantity, remainingStock, status, observedAt}` (`Cache-Control: no-store`) |
| `POST /api/v1/coupons/{couponId}/events` | 캠페인(회차) 생성 + Redis 초기화 | `{round, totalStock, openAt, closeAt}` | **201** `{eventId, ...}` |
| `POST /internal/campaigns/{eventId}/init` | 내부 운영용 Redis 재초기화 | - | **200**. `coupon.issue.admin.enabled=true` 일 때만 빈 등록 |

### Lua 판정 코드 ↔ HTTP 매핑

| 코드 | 이름 | HTTP |
|---:|---|---|
| 0 | `ACCEPTED` | 202 Accepted |
| 1 | `SOLD_OUT` | 409 Conflict |
| 2 | `ALREADY_ISSUED` | 409 Conflict |
| 3 | `EVENT_NOT_OPEN` | 400 Bad Request |
| 4 | `EVENT_CLOSED` | 400 Bad Request |
| 5 | `CAMPAIGN_NOT_INITIALIZED` | 404 (DB 에도 없음) / 503 (DB 에 존재) |
| 6 | `IDEMPOTENCY_CONFLICT` | 409 Conflict |
| 7 | `CORRUPTED_STATE` | 503 Service Unavailable |
| 8 | `PERSISTENCE_FAILED` | 500 Internal Server Error |

캠페인 생성 시 동일 `(couponId, round)` + 동일 설정 재요청은 멱등 성공,
다른 설정이면 `409 EVENT_CONFIGURATION_CONFLICT` 로 재고를 덮어쓰지 않습니다.

---

## 6. 테스트 자산과 재현 커맨드

테스트 파일 **45개** (테스트 클래스 43 + 픽스처 2, `src/test/java`). 무거운 테스트는 태그로 분리해 기본 `test` 에서 제외합니다.

| Gradle 태스크 | 무엇을 증명하나 | 조건 | 실행 |
|---|---|---|---|
| `test` | 단위 / 슬라이스 전체 | 태그 3종(`redis-integration`, `redis-benchmark`, `issuance-accuracy`) 제외 | `./gradlew test` |
| `redisIntegrationTest` | Lua 판정 정합성 (재고, 중복, 오픈시각, 멱등, 보상) | Docker Redis `6379` | `./gradlew redisIntegrationTest` |
| `issuanceAccuracyTest` | **Redis 승인 수 = MySQL 저장 수** | 기본 **20,000요청 / 재고 10,000 / 100스레드** | `./gradlew issuanceAccuracyTest` |
| `redisLuaBenchmark` | 최적화 전후 성능 비교 (ABBA-BAAB 교차, 각 1,000건 warm-up) | Redis `6380` benchmark 프로필 | `docker compose --profile benchmark up -d redis-benchmark` 후 `./gradlew redisLuaBenchmark` |

주요 테스트 클래스

| 클래스 | 검증 대상 |
|---|---|
| `RedisCouponIssueIntegrationTest` | Lua 판정 전 경로 |
| `SyncIssuanceAccuracyTest` / `RelayIssuanceAccuracyTest` | 두 저장 모드의 승인 수 = 저장 수 |
| `IssueStreamRelayIntegrationTest` | Stream Consumer 소비, XACK |
| `CouponIssueConstraintTest` | UNIQUE 4종 (`event_user`, `event_sequence`, `request_id`, `message_id`) |
| `IssuePersistenceProbeTest` | 저장 실패 후 `PERSISTED / ABSENT / UNVERIFIED` 판정 |
| `MaskingUtilTest` | 마스킹 정규식 15케이스 (오탐 방지 포함) |
| `GlobalExceptionHandlerTest` | 공통 응답 규격, 상태코드, 5xx 내부정보 비노출 |
| `RowLevelConsistencyVerificationIntegrationTest` | Testcontainers MySQL 기반 건별 검증 |
| `ConsistencyVerificationRunnerBatchTest` | Spring Batch 재시작, 실패 처리 |
| `CampaignRedisInitializationRecoverySchedulerTest` | DB 커밋 후 Redis 초기화 실패 복구 |

---

## 7. 정합성 검증 (300만 건 전수)

| 검사 | 무엇을 대조하나 | 스코프 |
|---|---|---|
| `StockConsistencyCheck` | `total_stock = 활성 발급 수 + remaining_stock`, `issued_quantity = 활성 발급 수` | EVENT / ALL |
| `DuplicateConsistencyCheck` | `(user_id, event_id)` 활성 발급 수 ≤ `per_user_limit` | EVENT / ALL |
| `CouponIssueStructuralConsistencyCheck` | `coupon_issue` 한 행의 필수값, 범위, 시각, 상태별 필드 조합 | EVENT / ALL |
| `CouponHistoryStructuralConsistencyCheck` | `coupon_history` 필수값, 시각 순서, 허용된 전이 형태 | EVENT / ALL |
| `CouponIssueHistoryStateConsistencyCheck` | 현재 상태 = 최신 이력의 도착 상태 (이력 없음도 위반) | EVENT / ALL |
| `CouponExpirationLagConsistencyCheck` | 만료 배치 허용 지연 초과 `ISSUED`, `valid_to` 이후 사용 | EVENT / ALL |

- 실행 구조: Spring Batch - 검사 1개당 Step 1개를 동적 조립(`ConsistencyBatchJobFactory`),
  `EventIdPageReader` 키셋 페이징(500건), `CheckResultAccumulatorWriter` 가 위반 수 + 샘플 20건 누적
- 결과 영속화: `VerificationResultEntity`(JSON diff) / 배치 실패는 `BatchFailureLogEntity`
- 범위: `Scope(EVENT | AS_OF_RANGE | ALL)` - `ALL` 로 300만 건 전수 실행, 실패 Job 재시작 지원
 (`ConsistencyVerificationRunnerBatchTest` 에서 재시작 후 완료까지 검증)

아직 없는 것

| 항목 | 상태 |
|---|---|
| **Redis ↔ MySQL 대조** | ❌ 검사 6종 전부 **DB↔DB**. `BITCOUNT` vs `COUNT(*)`, Redis `stock` vs `remaining_stock` 대조 없음 |
| 검증 실행 트리거 | ❌ `ConsistencyVerificationRunner` 를 `src/main` 에서 호출하는 곳 없음. 스케줄러, API 모두 없음 (현재는 테스트에서만 실행) |
| 위반 자동 복구 | ❌ 기록만 하고 교정하지 않음 |
| 검증 실패 알림 | ❌ `VerificationResultPersister` 의 발행 코드가 주석 처리 (알림 도메인 미머지) |
| 만료 배치 | ❌ 없음. `CouponExpirationLagConsistencyCheck` 는 존재하지 않는 배치의 지연을 감사하는 상태 |

---

## 8. 피드백 대응 현황

| # | 피드백 | 상태 | 대응 내용 / 증거 |
|---|---|:--:|---|
| 1.1 | 더미데이터 테스트로 아키텍처 타당성 검증 | ✅ | **300만 건 적재 상태의 본 서비스**(커밋 `e3ed53a`)를 재고 10,000 / 동시 20,000 으로 측정. SYNC p99 3,640ms, 커넥션 대기 135 vs RELAY p99 1,382ms, 대기 0. 주 측정 28회차 전부 발급 정확히 10,000, 에러 0 (§9) |
| 1.2 | 측정 근거로 아키텍처 추가, 개선 | ✅ | 측정으로 병목이 커넥션 풀임을 확인(대기 = 톰캣 200 - pool 이 3조건 전부 성립)하고 저장 경로를 `SYNC` / `RELAY` 로 분리. 커넥션 풀은 역산값 65 가 실측 포화점과 일치 |
| 1.3 | Kafka 유무 처리량, 지연 비교 | ❌ | `Ace_BE` 에 Kafka 파이프라인이 없어 미측정. 대신 **동기 vs 비동기(Redis Stream)** 비교는 완료. 적체 시 Redis 메모리 소요를 실측해(100만 건 약 90.9MB) Kafka 도입 판단 기준을 수치로 확보 (§9 S4, S5) |
| 1.4 | 중복 발행 제거, 이벤트 유실 방지 (오차 0) | ✅ | ① Lua 단일 원자 판정 + `XADD` 동봉(Outbox) ② 부분 쓰기 시 역순 롤백 ③ DB UNIQUE 4종 ④ 저장 실패 시 DB 재조회 후 `PERSISTED/ABSENT/UNVERIFIED` 판정 - `UNVERIFIED` 는 초과 발급 위험 때문에 자동 보상을 건너뛰고 `issue_failure_log` 에 남김 |
| 2.1 | 쿠폰 종류가 한 가지로 한정됨 | 🟡 | **1회차당 1종류 × 300회차 = 총 300종류**가 나가는 구조로 대응합니다 (§1). 할인 정책 유형(정률/정액/특가)을 늘리는 것은 이번 범위에 넣지 않았고, `coupon.type`(VARCHAR 20), `coupon.value`(BIGINT) 컬럼만 존재합니다. 현재 더미가 30회차뿐이라 재생성이 필요합니다 |
| 3.1 | 개발 전 테스트 시나리오 설계 + 개발 중 반복 수행 | ✅ | 태그 분리된 Gradle 태스크 4종 + 테스트 파일 45개. 발급 정확성(`issuanceAccuracyTest`)은 요구 수치(20,000/10,000)를 기본값으로 고정 |
| 3.2 | FE 화면 기반 동시성 / 정합성 검증 | 🟡 | 관리자 화면(캠페인 생성, 실시간 관제, 예약 오픈 관측, 동시 발급 시뮬레이터)이 `Ace_FE` `feat/1-manager-skeleton` 에 구현됨. API 계약 4개 일치 확인. **RELAY 모드는 상태 갱신 미구현 탓에 화면에서 발급 완료를 확인할 수 없음** (§10) |
| 3.3 | 사용자 수, 발급 수 규모 확대 재수행 | 🟡 | 회원 100만 / 이력 300만 적재 상태에서 동시 1,000 / 10,000 / 20,000 재수행 완료. **AWS 다중 인스턴스와 5만, 10만 규모는 계획만 존재** |
| 4.1 | 취소는 재고 반환이 아닌 '사용 취소' | ❌ | **현재 `StockConsistencyCheck` 는 `CANCELED` 를 재고 반납(활성 발급에서 제외)으로 계산 중** → 피드백과 상충. 상태 전이 구현과 함께 정책 변경 필요 |
| 4.2 | ISSUED 까지 실동작 + 상태변경 중복 반영 방지 | ✅ | 발급은 ISSUED 까지 실동작. `Idempotency-Key` + Lua requestId 결과 재반환 + UNIQUE `request_id`, `message_id`(`coupon_issue`) + `event_uid`(`coupon_history`) |
| 4.3 | 로그 개인정보 패턴 기반 마스킹 | 🟡 | 패턴 기반 마스킹 구현(email / name / phone) + 전역 예외 핸들러가 **예외 메시지를 로그에서 제외**하고 `incidentId` 로 추적 (`GlobalExceptionHandlerTest` 21케이스, `MaskingUtilTest`). **전역 방어(logback 마스킹 컨버터)는 미도입** - 핸들러를 거치지 않는 `log.error(..., ex)` 호출부에는 마스킹이 닿지 않음 |
| 4.4 | `max_connections` 기준 파드당 커넥션 역산 | ✅ | `max_connections 151` 실측, 여유 20 제외, 앱 2대로 역산해 **파드당 65**. pool 10 / 65 / 145 를 3회씩 재서 **65가 실제 포화점**임을 확인 (§9 S3) |
| 5.1 | DB / Redis 장애 복구 절차 | 🟡 | Redis 측: DB 커밋 후 초기화 실패 캠페인을 멱등 재초기화하는 복구 스케줄러 구현(`CampaignRedisInitializationRecoveryScheduler`, 다중 인스턴스 동시 재시도해도 재고 재충전 없음). **DB 장애 복구 절차는 미문서화** |
| 5.2 | 발급 완료 응답 후 장애 대응 | ✅ | 저장 예외가 나도 커밋 후 응답 유실일 수 있으므로 DB 재조회. 캠페인, 사용자, 순번까지 일치할 때만 `PERSISTED`. 같은 `requestId` 의 다른 발급이면 `ABSENT` 로 보고 재고, Bitmap 보상 |
| 5.3 | Redis 장애 메시지 유실 인위적 재현 + 사후 대응 | ✅ | **본 서비스 대상 강제 종료 실험 3회**: 약 9,830건이 미저장인 상태에서 저장 프로세스를 kill 해도 유실 0, 중복 0, 약 23 ~ 25초에 전량 복구 (§9 S6). 적체 한계도 실측(100만 건 약 90.9MB, `noeviction` 이라 메모리 포화 시 발급 전면 중단). 1단계 PoC 에서 AOF off 이면 전량 유실, `everysec` / `always` 는 유실 0 임을 확인 |

---

## 9. 부하 테스트 결과

측정 레포: [`Ace_LT`](https://github.com/URECA-Ace/Ace_LT).
더미 300만 건이 적재된 상태에서 측정했습니다.

공통 조건: 재고 10,000 고정, 회차는 측정마다 신규 생성, 3회 중앙값,
응답시간은 TTFB(`http_req_waiting`), 부하 모델은 `per-vu-iterations` (전원 t=0 동시 출발),
**정확성이 통과해야 성능 수치를 인정**.

> 앱, MySQL, Redis, k6 를 한 머신(10코어 / 16GB)에서 돌렸습니다.
> 절대값이 아니라 **비율**로 읽어야 합니다. 톰캣 스레드가 200이라 실제 동시 처리량도 200입니다.

### S1. SYNC vs RELAY (20,000 동시 / 재고 10,000 / pool 65 / 3회 중앙값)

| 지표 | SYNC (동기 저장) | RELAY (Stream 비동기) | 차이 |
|---|---:|---:|---|
| 응답 p99 | 3,640ms | **1,382ms** | **2.6배** |
| 응답 p50 | 1,986ms | 1,205ms | 1.6배 |
| 처리량 | 3,386 TPS | **4,457 TPS** | 1.3배 |
| DB 커넥션 대기 스레드 | **135** | **0** | 병목 소멸 |
| 실제 사용 커넥션 | 65 (포화) | **1** | 요청 중 DB 미사용 |
| 에러 / 타임아웃 | 0 / 0 | 0 / 0 | 양쪽 무사 |
| 발급 수 | 정확히 10,000 | 정확히 10,000 | **초과 0** |

3회 원본 p99: SYNC 3,524 / 4,254 / 3,640, RELAY 1,859 / 1,382 / 1,143.

### 부하 증가 추이 (pool 10)

| 동시 접속 | SYNC p99 | RELAY p99 | SYNC TPS | RELAY TPS |
|---:|---:|---:|---:|---:|
| 1,000 | 559ms | 106ms | 1,716 | 6,966 |
| 10,000 | 5,071ms | 819ms | 1,836 | 7,195 |
| 20,000 | 8,294ms | 1,295ms | 1,716 | 4,872 |

SYNC 는 부하를 20배 늘려도 처리량이 1,716 TPS 에서 늘지 않습니다. 커넥션 수가 천장입니다.

### S3. 커넥션 풀 크기 근거 (3회 x 2모드 x 3크기)

| pool | SYNC 대기 | SYNC p99 | RELAY 대기 | RELAY p99 | 비율 |
|---:|---:|---:|---:|---:|---:|
| 10 | **190** | 8,294ms | 0 | 1,295ms | 6.4배 |
| **65** (역산값) | **135** | **3,640ms** | 0 | **1,382ms** | **2.6배** |
| 145 | **55** | 4,020ms | 0 | 2,749ms | 1.5배 |

- `대기 = 톰캣 스레드 200 - pool 크기` 가 세 조건 전부 오차 없이 성립 (190 / 135 / 55)
- 역산으로 나온 **65가 실제 포화점**이었습니다. 65와 145의 p99 분포가 겹치므로
 "145가 더 나쁘다" 가 아니라 **"65 이상은 더 좋아지지 않는다"** 가 맞는 서술입니다
- pool 을 6.5배로 키운 SYNC(3,640ms)가 pool 10 인 RELAY(1,295ms)를 못 따라잡습니다
- RELAY 는 세 조건 전부 대기 0 / 사용 커넥션 1 = 커넥션 풀이 병목이 아니라는 증거
- **인용 수치는 2.6배**입니다. pool 10 의 6.4배는 병목을 눈에 보이게 한 조건으로만 씁니다

### S2. 정확성 (매 회차 자동 검사 + 파괴 시험)

| 검사 | 기준 | SYNC | RELAY |
|---|---|---|---|
| 초과 발급 | 승인 <= 재고 | 정확히 10,000 | 정확히 10,000 |
| 발급 수 일치 | DB 기록 = 승인 응답 수 | 통과 | 통과 |
| 발급 대 이력 | 1 대 1 | 10,000 / 10,000 | 10,000 / 10,000 |
| 1인 1매 | 1,000 VU 가 userId 100개 공유 | 발급 정확히 100 | 발급 정확히 100 |
| 멱등 | 같은 요청 번호 재요청 | 같은 응답, 행 증가 0 | 동일 |
| 실패 로그 | 0건 | 0 | 0 |

주 측정 28회차 전부 5xx 0건, 타임아웃 0건. 하나라도 깨지면 그 회차 수치는 폐기합니다.

### RELAY 문제: 저장 지연

`recorded_at - occurred_at` (pool 65, 3회)

| 모드 | p50 | p95 | p99 | max |
|---|---:|---:|---:|---:|
| SYNC | 47 ~ 48ms | 103 ~ 137ms | 156 ~ 506ms | 326 ~ 710ms |
| **RELAY** | **10.6 ~ 11.7초** | 16.2 ~ 17.6초 | **16.7 ~ 18.2초** | 17.0 ~ 18.4초 |

"발급 완료" 응답 후 최대 18초간 DB 에 행이 없습니다.
원인은 릴레이가 **단일 스레드로 100건씩** 처리하기 때문입니다 (100회 x 약 180ms = 약 18초).
행당 속도는 정상이라 **병렬화로 줄일 수 있는 서비스 측 개선 과제**입니다.

### S4. 적체 시 Redis 메모리 (SYNC 모드로 측정, XADD 는 모드와 무관하게 실행)

| 적체 건수 | Stream 키 바이트 | used_memory 증가 | 엔트리당 (Stream 키) | 엔트리당 (used_memory) |
|---:|---:|---:|---:|---:|
| 2,000 | 185,884 | 475,904 | 92.9 B | 238.0 B |
| 20,000 | 1,906,112 | 4,556,240 | 95.3 B | 227.8 B |

Stream 키 바이트 기준 외삽: 10만 건 약 9.1MB, **100만 건 약 90.9MB**, 1,000만 건 약 908.9MB.
`used_memory` 기준으로는 그 약 2.4배(100만 건 약 217MB)로 잡아야 합니다.
단편화와 클라이언트 버퍼가 함께 잡히므로 **운영 산정은 used_memory 기준이 안전**합니다.
측정 시 Redis 는 `maxmemory=0` (무제한), `maxmemory-policy=noeviction`.
운영에서 상한을 걸면 **메모리가 차는 순간 쓰기 거부 -> 판정 Lua 실패 -> 발급 전면 중단**입니다.

### S6. 저장 프로세스 강제 종료 후 복구 (RELAY, 3회)

| 회차 | 승인 | 종료 시점 DB | 미저장 | 복구 후 DB | 이력 | 고유 사용자 | 실패 | 복구 시간 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 10,000 | 164 | **9,836** | **10,000** | 10,000 | 10,000 | 0 | 22.8초 |
| 2 | 10,000 | 158 | **9,842** | **10,000** | 10,000 | 10,000 | 0 | 24.9초 |
| 3 | 10,000 | 171 | **9,829** | **10,000** | 10,000 | 10,000 | 0 | 24.0초 |

**약 9,830건이 미저장 상태에서 프로세스를 강제 종료해도 유실 0, 중복 0, 3회 전부 복구.**
복구 후 Redis pending 0, sequence 10,000. SYNC 대조군은 미저장 구간 자체가 없습니다.

### S5. Kafka 유무 비교: 미측정

원래는 여러 소비자에게 이벤트를 뿌리려고 Kafka 를 넣을 계획이었는데,
재보니 Redis Stream 만으로 이미 충분했습니다(적체 100만 건에 91MB).
Stream 만으로 먼저 만들어보고, 그래도 문제가 있으면 Kafka 를 붙여 비교할 예정입니다.
자세한 건 LT 레포 리드미에 올릴 예정

### 아직 남은 측정

| 항목 | 상태 |
|---|---|
| AWS 다중 인스턴스 / 5만, 10만 규모 | 계획만 존재 |
| 결과 표, 그래프 자동 생성 | 파서 스크립트 미작성 |

### 참고: 1단계 비교 PoC (연습용 앱 대상)

"왜 Redis 인가", "왜 저장을 분리하는가" 에 답하기 위해 별도 연습 앱으로 4종을 비교했습니다.
**데이터 조건이 달라 본 시스템 수치와 절대값을 섞어 인용하지 않습니다.**

| MySQL 비관적 락 | + Redis 판정 | + 비동기 저장 | + 내구 로그 |
|---|---|---|---|
| p99 14,203ms (에러율 50%) | 4,506ms | 1,101ms | 846ms |

- MySQL 락은 20,000 동시에서 에러율 50%, 거절(409) 응답조차 0건. Redis 판정은 0%
- 프로세스 강제 종료 시 인메모리 큐는 전량 유실, Redis Stream 은 유실 0
- **Redis AOF off 이면 브로커 장애 시 전량 유실**, `everysec` / `always` 는 유실 0.
 내구성 대가는 p99 1,502 / 1,265 / 1,254ms 로 노이즈 수준
- 앱 2대 분리 시 p99 45% 개선, 반대로 커넥션만 늘리면 악화 (에러 6% -> 24%)
- 전 회차, 전 부하, 로컬과 AWS 양쪽에서 초과 발급 0건

---

## 10. 알려진 이슈 & 남은 일

### 코드

| 항목 | 내용 |
|---|---|
| issue #28 | Row-level 검증의 `message_id` 정규식이 PR #26 의 결정적 UUID 계약과 불일치 → 정상 데이터가 `INVALID_MESSAGE_ID_FORMAT` 으로 오검출될 수 있음 |
| 상태 전이 | USED / CANCELED / EXPIRED 전이 코드 없음 (브랜치 `feat/25-coupon-usage-cancellation` 미머지) |
| 취소 정책 | `StockConsistencyCheck` 의 CANCELED = 재고 반납 전제를 '사용 취소' 개념으로 변경 필요 |
| 마스킹 | DB 예외 메시지, 스택트레이스 마스킹, logback 마스킹 컨버터 도입 |
| 미머지 PR | #14 알림 Mocking, #20 이벤트/로그 기반 정합성 검증 |
| Kafka | 브로커만 기동. Producer/Consumer/DLQ 미구현 |
| DLQ | RELAY 재시도 3회 초과 시 보상 + `issue_failure_log` 로 종결. 별도 dead-letter 경로 없음 |
| 요청 상태 확정 | Redis 요청 상태가 `ACCEPTED` 에서 더 올라가지 않음. DB 저장 완료 후 `ISSUED` 로 갱신하는 처리 미구현 → RELAY 모드에서 상태 조회로 저장 완료를 알 수 없음 |
| 검증 실행 경로 | 정합성 검증을 실행할 스케줄러, API 없음 (§7) |
| `perUserLimit` | 컬럼은 있으나 생성 API 에 노출되지 않고 코드에서 1로 고정. Lua 비트맵도 1매 초과를 표현하지 못함 |
| `erd.sql` | 현재 엔티티와 불일치(구버전 테이블, 컬럼) |

### 더미 데이터

현재 덤프에는 검증을 무의미하게 만드는 결함이 있어 재생성이 필요합니다.

| 결함 | 건수 | 비율 |
|---|---:|---:|
| `issued_at` 이 해당 회차 기간(`open_at`~`close_at`) 밖 | 2,899,773 | 96.7% |
| `status='ISSUED'` 인데 `valid_to` 경과 | 1,243,947 | 41.5% |
| `status='EXPIRED'` 인데 `valid_to` 미경과 | 14,175 | 0.5% |
| `coupon_history` 행 없음 | 0건 존재 | - |

정상 확인된 항목: `valid_to = issued_at + 24h` 위반 0, `used_at` 유효기간 이탈 0,
미래 발급 0, `(event_id, user_id)` 중복 0.

→ **300회차 / 회차당 10,000장 (총 300종류)** 으로 재생성하면서 위 결함과 1번 목치의 구성 차이를 함께 해소합니다.

### 프론트엔드

관리자 화면은 `Ace_FE` 의 **`feat/1-manager-skeleton`** 브랜치에 구현되어 있습니다

| 구성 | 내용 |
|---|---|
| `src/api/couponApi.js` | 공통 `request()` + `ApiError`. 응답 봉투 `result`, `data`, `error{code,message,incidentId}` 를 그대로 해석 |
| `src/App.jsx` (1,068줄) | 캠페인 생성 폼, 발급 요청, 상태 폴링, 멱등 재시도, 동시 발급 시뮬레이터(기본 20,000 참가자 / 동시 128) |
| `src/components/CampaignMonitor.jsx` | `issuance-stats` 1초 폴링 관제. `AbortController` 로 정리, `EVENT_NOT_FOUND` 시 폴링 중단 |
| `src/components/ScheduledOpenTimeline.jsx` | 예약 오픈 상태 전이(SCHEDULED → OPEN → SOLD_OUT → CLOSED) 관측 기록 |

호출하는 API 4개는 백엔드 계약과 **일치**합니다.

| FE 호출 | BE 엔드포인트 | 계약 일치 |
|---|---|---|
| `issueCoupon` | `POST /api/v1/events/{eventId}/issues` | 헤더 `Idempotency-Key`, 본문 `{userId}` 일치 |
| `getIssueStatus` | `GET /api/v1/events/{eventId}/issues/{requestId}` | 일치 |
| `getIssuanceStats` | `GET /api/v1/events/{eventId}/issuance-stats` | `status`, `remainingStock`, `observedAt` 필드 일치 |
| `createCouponEvent` | `POST /api/v1/coupons/{couponId}/events` | `{round, totalStock, openAt, closeAt}` 일치. 응답 `eventId`, `round`, `remainingStock`, `openAt`, `closeAt` 모두 존재 |

FE 가 참조하는 에러 코드(`EVENT_NOT_FOUND`, `EVENT_STATS_TEMPORARILY_UNAVAILABLE`)도
`ErrorCode` enum 에 실재합니다. `/internal/campaigns/**` 는 호출하지 않습니다.

**교차 확인에서 나온 문제 2가지**

| 문제 | 내용 | 대응 |
|---|---|---|
| **RELAY 모드에서 발급 완료가 영원히 안 뜸** | FE 는 상태가 `ACCEPTED` 또는 `PROCESSING` 인 동안 3초 간격으로 폴링하고 `ISSUED` 를 완료로 셉니다. 그런데 백엔드는 **DB 저장 후 Redis 요청 상태를 갱신하지 않습니다** (§10 "요청 상태 확정"). 폴링이 끝나지 않고 완료 카운터가 0 으로 고정됩니다 | 저장 완료 시 요청 상태를 `ISSUED` 로 올리는 처리 구현 |
| 빌드 후 배포하면 요청 전량 차단 | `VITE_API_BASE_URL` 을 직접 지정하면 dev 프록시를 안 거치는데, 백엔드에 CORS 설정이 없습니다. 개발 중에는 프록시가 same-origin 이라 드러나지 않습니다 | `WebMvcConfigurer` CORS 설정 추가 |

`VITE_API_TARGET` 은 `loadEnv` 가 아니라 `process.env` 로 읽으므로 `.env` 가 아닌 셸 환경변수여야 합니다.

---

## 11. 실행 방법

```bash
# 1. 인프라 (MySQL 3307 / Redis 6379 / Kafka 9092)
docker compose up -d

# 2. 설정 - application.properties 는 gitignore 대상
cp src/main/resources/application-example.properties src/main/resources/application.properties
#   → DB 계정, consistency.expiration.allowed-delay-ms 를 채운다

# 3. 더미 데이터 복원 (manual/CREATE_DUMP_DATA.md 참고)
docker compose --profile restore run --rm restore && docker compose restart mysql

# 4. 빌드 / 기동
./gradlew build
./gradlew bootRun

# 5. 검증
./gradlew test
./gradlew redisIntegrationTest      # Docker Redis 필요
./gradlew issuanceAccuracyTest      # MySQL + Redis 필요. 20,000요청 / 재고 10,000
```

부하 측정 시에는 전용 프로필을 씁니다.

```bash
java -jar build/libs/Ace_BE-0.0.1-SNAPSHOT.jar \
 --spring.profiles.active=load \
 --spring.datasource.hikari.maximum-pool-size=65 \
 --coupon.issue.persistence.mode=RELAY
```

### 참고 문서

- [`docs/redis-coupon-issue.md`](docs/redis-coupon-issue.md) - Redis 키 규칙, Lua 반환 코드, 원자성 범위, 보상 판정
- [`docs/performance/redis-lua-performance.md`](docs/performance/redis-lua-performance.md) - Lua v1 vs v2 성능 비교와 측정 방법
- [`manual/DOCKER_COMPOSE.md`](manual/DOCKER_COMPOSE.md) - 인프라 사용법
- [`manual/CREATE_DUMP_DATA.md`](manual/CREATE_DUMP_DATA.md) - 더미 데이터 복원
