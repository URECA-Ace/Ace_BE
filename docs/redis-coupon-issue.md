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
| 5 | `CAMPAIGN_NOT_INITIALIZED` | 503 Service Unavailable |
| 6 | `IDEMPOTENCY_CONFLICT` | 409 Conflict |
| 7 | `CORRUPTED_STATE` | 503 Service Unavailable |
| 8 | `PERSISTENCE_FAILED` | 500 Internal Server Error |

반환 배열은 `[code, issueSequence, remainingStock, decidedAtEpochMillis]` 순서다. 값이 없는 순번과 재고는 `-1`이다.

## 원자성 범위

`coupon-issue.lua`는 Redis 서버 시각 판정, 기존 requestId 결과 반환, Bitmap 중복 확인, 재고 `DECR`, 순번 `INCR`, Bitmap 기록, Stream 적재, 요청 상태 기록을 한 번의 실행으로 처리한다. 승인과 비동기 저장 대기열 적재 사이의 유실 구간이 없다.

Redis Lua는 실행 중 다른 명령의 개입은 차단하지만 런타임 오류 발생 시 앞선 쓰기를 자동 롤백하지 않는다. 이를 줄이기 위해 모든 키 타입과 숫자 상태를 첫 쓰기 전에 검증한다. 운영 Redis는 해당 namespace의 외부 쓰기 차단, `maxmemory-policy noeviction`, AOF 내구성, 메모리 임계치 모니터링을 전제로 한다. Docker Compose의 `appendfsync everysec`는 처리량을 우선한 설정으로 최대 약 1초의 장애 손실 가능성이 있으므로, 복제본과 Redis-DB 정합성 복구 작업이 필요하다. `appendfsync always`는 내구성을 높이는 대신 쓰기 지연이 증가한다.

초기화 Lua는 동일 설정 재실행만 멱등 성공으로 처리한다. 다른 재고나 시간 설정 및 부분 잔존 키는 덮어쓰지 않고 충돌로 종료한다. 모든 판정 키는 캠페인 마감 시각과 보존 기간을 합친 절대 시각에 만료된다.

## STS 실행 및 검증

1. `Ace_BE` 프로젝트를 Gradle Project로 가져오기
2. `docker compose up -d redis` 실행
3. STS의 Gradle Tasks에서 `verification > redisIntegrationTest` 실행
4. 전체 단위 테스트는 `verification > test` 실행

상태 조회 API는 `GET /api/v1/events/{eventId}/issues/{requestId}`다.
