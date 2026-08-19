# Redis 쿠폰 발급 규칙

## 키 규칙

모든 키는 `coupon:{campaign:<campaignId>}:<purpose>` 형식이다. 중괄호 영역은 Redis Cluster hash-tag이며, 한 캠페인의 Lua 입력 키를 동일 슬롯에 고정한다.

| 목적 | 키 형식 | 자료구조 |
|---|---|---|
| 캠페인 설정 | `coupon:{campaign:13}:meta` | Hash |
| 잔여 재고 | `coupon:{campaign:13}:stock` | String |
| 발급 순번 | `coupon:{campaign:13}:sequence` | String |
| 사용자 발급 여부 | `coupon:{campaign:13}:issued:bitmap:<segment>` | Bitmap |
| requestId 상태 | `coupon:{campaign:13}:requests` | Hash |
| 비동기 저장 대기열 | `coupon:{campaign:13}:issue-stream` | Stream |

사용자 ID는 `(userId - 1)`을 기준으로 8,388,608 bit 단위 세그먼트와 오프셋으로 변환한다. 사용자 ID가 커져도 하나의 Bitmap이 최대 1 MiB를 넘지 않는다. 캠페인별 requestId 범위는 상태 조회 API 경로와 동일하다.

## Lua 반환 코드

| 코드 | 이름 | API 결과 |
|---:|---|---|
| 0 | `ACCEPTED` | 202 Accepted |
| 1 | `SOLD_OUT` | 409 Conflict |
| 2 | `ALREADY_ISSUED` | 409 Conflict |
| 3 | `EVENT_NOT_OPEN` | 400 Bad Request |
| 4 | `EVENT_CLOSED` | 400 Bad Request |
| 5 | `CAMPAIGN_NOT_INITIALIZED` | DB에도 없으면 404, DB에 존재하면 503 |
| 6 | `IDEMPOTENCY_CONFLICT` | 409 Conflict |
| 7 | `CORRUPTED_STATE` | 503 Service Unavailable |
| 8 | `PERSISTENCE_FAILED` | 500 Internal Server Error |

반환 배열은 `[code, issueSequence, remainingStock, decidedAtEpochMillis]` 순서다. 값이 없는 순번과 재고는 `-1`이다.

## 원자성 범위

`coupon-issue.lua`는 Redis 서버 시각 판정, 기존 requestId 결과 반환, Bitmap 중복 확인, 재고 `DECR`, 순번 `INCR`, Bitmap 기록, Stream 적재, 요청 상태 기록을 한 번의 실행으로 처리한다. 승인과 비동기 저장 대기열 적재 사이의 유실 구간이 없다.

Redis Lua는 실행 중 다른 명령의 개입은 차단하지만 런타임 오류 발생 시 앞선 쓰기를 자동 롤백하지 않는다. 실제 읽기 명령을 `redis.pcall`로 실행해 타입과 숫자 상태를 첫 쓰기 전에 검증하고, 동적 키 검증은 승인 경로로 지연한다. 운영 Redis는 해당 namespace의 외부 쓰기 차단, `maxmemory-policy noeviction`, AOF 내구성, 메모리 임계치 모니터링을 전제로 한다. Docker Compose의 `appendfsync everysec`는 처리량을 우선한 설정으로 최대 약 1초의 장애 손실 가능성이 있으므로, 복제본과 Redis-DB 정합성 복구 작업이 필요하다. `appendfsync always`는 내구성을 높이는 대신 쓰기 지연이 증가한다.

요청 상태는 `userId|resultCode|statusCode|sequence|remainingStock|decidedAt` 6필드로 저장한다. 숫자 상태 코드와 미사용 Stream ID 제거로 직렬화 크기를 줄였으며, 롤링 배포 중의 기존 7필드·문자열 상태 데이터도 계속 읽을 수 있다.

초기화 Lua는 동일 설정 재실행만 멱등 성공으로 처리한다. 다른 재고나 시간 설정 및 부분 잔존 키는 덮어쓰지 않고 충돌로 종료한다. 모든 판정 키는 캠페인 마감 시각과 보존 기간을 합친 절대 시각에 만료된다.

## 캠페인 생성 API

기존 쿠폰 마스터에 발급 캠페인을 추가하려면 `POST /api/v1/coupons/{couponId}/events`를 호출한다.

```json
{
  "round": 24,
  "totalStock": 10000,
  "openAt": "2026-08-20T00:00:00+09:00",
  "closeAt": "2026-08-20T23:59:59+09:00"
}
```

캠페인은 1인 1매 정책으로 생성된다. 서비스는 MySQL 트랜잭션을 먼저 커밋해 `eventId`를 확보한 다음 그 식별자로 Redis 판정 상태를 초기화한다. 동일 `(couponId, round)`와 동일 설정의 재요청은 기존 캠페인을 재사용하고 Redis 초기화를 다시 시도한다. 다른 설정의 동일 회차 요청은 기존 재고를 덮어쓰지 않고 `409 EVENT_CONFIGURATION_CONFLICT`로 거절한다.

DB 커밋 후 Redis 초기화에 실패하면 캠페인 데이터는 복구 기준으로 유지되고 API는 `503 CAMPAIGN_INITIALIZATION_TEMPORARILY_UNAVAILABLE`를 반환한다. `coupon.campaign.redis-initialization-recovery.enabled=true`이면 복구 스케줄러가 마감 전 `SCHEDULED`, `OPEN` 캠페인을 대상으로 멱등 초기화를 재시도한다. 여러 애플리케이션 인스턴스가 동시에 재시도해도 초기화 Lua가 동일 설정만 허용하므로 재고를 다시 채우지 않는다.

## STS 실행 및 검증

1. `Ace_BE` 프로젝트를 Gradle Project로 가져오기
2. `docker compose up -d redis` 실행
3. STS의 Gradle Tasks에서 `verification > redisIntegrationTest` 실행
4. 전체 단위 테스트는 `verification > test` 실행

성능 비교는 `docker compose --profile benchmark up -d redis-benchmark` 실행 후 STS의 Gradle Tasks에서 `verification > redisLuaBenchmark`를 실행한다. 교차 측정과 동일한 수치의 최적화 전·후 개별 캡처용 리포트도 함께 생성된다. 발표용 HTML·CSV는 `build/reports/redis-lua-benchmark` 경로에 생성되며, 세부 측정 조건과 결과는 `docs/performance/redis-lua-performance.md`에 정리해 두었다.

상태 조회 API는 `GET /api/v1/events/{eventId}/issues/{requestId}`다.
