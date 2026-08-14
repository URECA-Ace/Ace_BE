# Redis Lua 최적화 전후 성능 비교

## 측정 결과

| 버전 | 처리량 | P50 | P95 | P99 | 최대 지연 |
|---|---:|---:|---:|---:|---:|
| 최적화 전 | 7,549.12 req/s | 15.941 ms | 26.459 ms | 31.218 ms | 41.627 ms |
| 최적화 후 | 8,069.51 req/s | 15.232 ms | 23.774 ms | 29.252 ms | 37.439 ms |
| 개선율 | **+6.89%** | **4.45% 감소** | **10.15% 감소** | **6.30% 감소** | **10.06% 감소** |

20,000건 요청과 10,000장 재고에서 승인 10,000건, 재고 소진 10,000건, 초과 발급 0건을 모두 확인했다. 최종 재고, Bitmap bit 수, 발급 순번, Stream 엔트리 수도 승인 수와 일치했다.

![Redis Lua 최적화 전후 성능 비교](redis-lua-performance.png)

## 최적화 내용

- 멱등 재요청의 요청 상태 선조회 및 즉시 반환
- `HMGET` 한 번으로 requestId와 요청 스키마 동시 검증
- 실제 읽기 명령과 `redis.pcall` 기반 정적 키 `TYPE` 호출 제거
- 승인 경로로 Bitmap 존재 및 Stream 타입 검증 지연
- 문자열 상태명 대신 숫자 상태 코드 저장
- 요청 상태의 미사용 Stream ID 제거와 7필드에서 6필드로 축소
- 기존 7필드·문자열 상태 데이터 호환 유지

## 측정 방법

- Redis 8.2 전용 벤치마크 프로필, 호스트 포트 6380
- Lua 실행 비용 격리용 AOF/RDB 비활성화
- 128개 물리 Lettuce 연결의 `EVALSHA` 호출
- 각 버전 1,000건 warm-up 후 ABBA-BAAB 교차 실행
- 최적화 전 스크립트 고정본과 현재 운영 스크립트의 동일 조건 비교

이 결과는 HTTP, JSON 직렬화, Spring MVC, DB/Kafka 비용을 제외한 Redis Lua 판정 구간 전용 벤치마크다. 전체 API TPS는 JMeter 부하 테스트로 별도 측정해야 한다.

## 재현 방법

```powershell
docker compose --profile benchmark up -d redis-benchmark
./gradlew.bat redisLuaBenchmark --no-daemon --rerun-tasks "-Dbenchmark.mode=compare" "-Dbenchmark.requests=20000" "-Dbenchmark.workers=128"
```

실행 후 각 결과는 다음 파일에서 확인할 수 있다.

- HTML 발표용 리포트: `build/reports/redis-lua-benchmark/index.html`
- 수치 원본: `build/reports/redis-lua-benchmark/results.csv`
- 동일 교차 측정의 최적화 전 단독 리포트: `build/reports/redis-lua-benchmark/before/index.html`
- 동일 교차 측정의 최적화 후 단독 리포트: `build/reports/redis-lua-benchmark/after/index.html`
- Gradle 테스트 리포트: `build/reports/tests/redisLuaBenchmark/index.html`
- 발표용 캡처 이미지: `docs/performance/redis-lua-performance.png`

정합성 테스트는 AOF가 활성화된 일반 개발 Redis 6379에서 별도 실행한다.

```powershell
./gradlew.bat redisIntegrationTest --no-daemon
```
