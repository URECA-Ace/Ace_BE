# 📊 모니터링 환경 (Prometheus + Grafana) 실행 가이드

본 문서는 로컬 환경에서 백엔드 서버의 상태와 쿠폰 발급/상태변경/정합성 검증 관련 비즈니스 지표를 모니터링하기 위한 가이드입니다.

## 1. 사전 준비 (인프라 실행)
모니터링을 위해서는 백엔드가 사용하는 인프라(MySQL, Redis)와 함께 Prometheus, Grafana가 실행되어야 합니다.

```bash
# 백그라운드로 전체 컨테이너 실행
docker-compose up -d
```
> [!WARNING]
> 이미 `ace-grafana` 컨테이너가 떠 있는 상태에서 `docker/grafana/` 아래 설정을 새로 받은 경우, 단순 재시작으로는 볼륨 마운트가 갱신되지 않습니다. 아래처럼 컨테이너를 재생성해야 데이터소스/대시보드 자동 설정이 반영됩니다.
> ```bash
> docker-compose up -d --force-recreate grafana
> ```

## 2. 백엔드 서버 실행
IDE(IntelliJ 등)에서 `AceBeApplication`을 실행합니다.
로컬 DB 연결이 필요한 경우 `src/main/resources/application.properties`의 설정이 알맞게 되어 있는지 확인하세요.

## 3. 접속 테스트
서버가 기동되면 아래 링크들에 순서대로 접속하여 상태를 확인합니다.

1. **Spring Boot Actuator 지표 노출 확인**
   - [http://localhost:8080/actuator/prometheus](http://localhost:8080/actuator/prometheus)
   - 접속 시 `jvm_memory_used_bytes` 등의 텍스트가 빽빽하게 출력되면 정상입니다.
   - 아래 4절의 커스텀 지표(`coupon_issue_total` 등)도 실제 API를 한 번 이상 호출해야 값이 찍힙니다. (Counter라 호출 전에는 아예 노출되지 않습니다)
2. **Prometheus 수집 확인**
   - [http://localhost:9090](http://localhost:9090) 접속
   - 상단 메뉴 **Status** -> **Targets** 클릭
   - `ace-backend` 항목의 State가 초록색 **UP**인지 확인합니다.
   - `docker/prometheus/prometheus.yml`의 `scrape_interval`은 `5s`로 설정되어 있습니다.

## 4. 커스텀 비즈니스 지표
아래 4개 지표는 Micrometer `Counter`로 계측되어 있으며, 이름의 `.`은 Prometheus로 노출될 때 `_`로 바뀌고 끝에 `_total`이 붙습니다.

| Micrometer 이름 | Prometheus 이름 | 태그 | 설명 |
|---|---|---|---|
| `coupon.issue` | `coupon_issue_total` | `result`(success/fail), `reason`(실패 시 `ErrorCode`) | 쿠폰 발급 요청의 성공/실패 |
| `coupon.state.change` | `coupon_state_change_total` | `result`, `from`, `to`, `reason`(실패 시) | 쿠폰 상태 변경(`ISSUED`→`USED` 등) 성공/실패 |
| `consistency.verification` | `consistency_verification_total` | `check`(체크 이름), `status`(`PASS`/`FAIL`/`ERROR`), `scope`(`EVENT`/`AS_OF_RANGE`/`ALL`) | 정합성 검증 결과 (수동/스케줄/재검증/배치 모든 트리거 공통) |
| `coupon.issue.relay` | `coupon_issue_relay_total` | `result`, `reason`(실패 시) | **RELAY 모드 전환 대비.** Redis Stream 릴레이의 비동기 저장·확정 최종 결과. 현재 배포 모드는 `SYNC`라 데이터가 찍히지 않으며, `coupon.issue.persistence.mode=RELAY`로 전환한 뒤부터 값이 쌓입니다. |

> `coupon.issue`의 `success`는 SYNC 모드 기준 "요청 판정 + DB 저장까지" 성공을 의미합니다. RELAY 모드로 전환하면 `coupon.issue`의 `success`는 "요청 판정"만 반영하고, 실제 비동기 저장의 최종 성공/실패는 `coupon.issue.relay`로 따로 봐야 합니다.

## 5. Grafana 대시보드 확인
데이터소스와 대시보드는 `docker/grafana/provisioning/`, `docker/grafana/dashboards/`를 통해 **자동으로 설정**되므로 수동으로 만들 필요가 없습니다.

1. [http://localhost:3000](http://localhost:3000) 접속 (초기 계정: `admin` / `admin`)
2. 좌측 메뉴(≡) -> **Dashboards** 이동 -> **Ace Coupon Metrics** 대시보드가 이미 등록되어 있습니다.
3. 데이터소스도 **Connections** -> **Data sources**에 `Prometheus`가 이미 연결되어 있습니다. (연결이 안 되어 있다면 위 1절의 컨테이너 재생성을 먼저 확인하세요)

**Ace Coupon Metrics 대시보드 패널 구성** (`docker/grafana/dashboards/ace-coupon-metrics.json`, 10초 자동 새로고침)

| 패널 | 내용 |
|---|---|
| 쿠폰 발급 현황 (성공/실패) | `coupon_issue_total`의 성공/실패 초당 건수 |
| 쿠폰 발급 실패 사유별 | 실패를 `reason`(ErrorCode)별로 분리 |
| 쿠폰 상태 변경 현황 (전이별) | `coupon_state_change_total` 성공 건을 `from`→`to`별로 분리 |
| 쿠폰 상태 변경 실패 사유별 | 실패를 `reason`별로 분리 |
| 정합성 검증 현황 (체크/상태) | `consistency_verification_total`을 `check`, `status`, `scope`별로 분리 |
| RELAY 비동기 저장 결과 | `coupon_issue_relay_total` — RELAY 모드 전환 후에만 값이 생김 |

각 패널은 `$__rate_interval`을 사용하므로, 대시보드 상단 Time range를 좁히면(예: Last 5 minutes) 초 단위에 가깝게, 넓히면(예: Last 6 hours) 분 단위에 가깝게 자동으로 조정됩니다. 특정 단위로 고정하고 싶다면 패널 편집 -> Query options -> **Min interval**에 `5s` 또는 `1m`처럼 직접 입력하면 됩니다. (Prometheus의 `scrape_interval`이 5s이므로 그보다 더 잘게는 의미가 없습니다)

> [!NOTE]
> JVM 메모리, HTTP 트래픽, HikariCP(DB 커넥션) 같은 범용 지표를 보고 싶다면, **Dashboards** -> **New** -> **Import**에서 그라파나 공식 템플릿 `4701`(JVM Micrometer)을 추가로 Import해서 사용할 수 있습니다. 이 문서의 커스텀 대시보드와는 별개로 필요할 때만 추가하면 됩니다.

## 6. 트러블슈팅

**대시보드/데이터소스가 안 보여요**
- Grafana 컨테이너가 provisioning 설정을 반영하기 전(예: `docker/grafana/` 추가 전)에 이미 떠 있던 경우입니다. 1절의 `--force-recreate` 명령으로 컨테이너를 다시 만드세요.

**커스텀 지표(`coupon_issue_total` 등)가 Prometheus/Grafana에 안 보여요**
- Counter는 최소 한 번 이상 호출(증가)되기 전에는 `/actuator/prometheus`에 아예 노출되지 않습니다. 관련 API(쿠폰 발급/상태 변경/정합성 검증)를 한 번 호출한 뒤 다시 확인하세요.
- `management.endpoints.web.exposure.include`에 `prometheus`가 포함되어 있는지 `application.properties`에서 확인하세요.

**`ace-backend` Target이 DOWN이에요**
- 백엔드 서버가 8080 포트로 떠 있는지 확인하세요. `docker/prometheus/prometheus.yml`은 `host.docker.internal:8080`을 바라보므로, 서버를 컨테이너가 아니라 로컬(IDE)에서 직접 띄우는 구성을 전제로 합니다.
