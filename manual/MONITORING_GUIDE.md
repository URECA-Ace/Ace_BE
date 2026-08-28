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
   - 스크레이핑 주기는 `docker/prometheus/prometheus.yml`의 `scrape_interval` 값을 따릅니다. 서브초(예: `100ms`) 단위로 내리는 건 권장하지 않습니다 — `/actuator/prometheus`가 매 스크레이프마다 등록된 전체 지표를 텍스트로 직렬화해서 응답하기 때문에 백엔드에 부하가 걸리고, Prometheus에 쌓이는 샘플 수도 그만큼 늘어납니다. 진짜 초 단위 미만의 실시간성이 필요하면 Prometheus를 거치지 않는 별도 push 경로(WebSocket/SSE)가 더 맞는 방향입니다.

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
| 쿠폰 발급 현황 (접수/저장 · 성공/실패) | `coupon_issue_total`(Redis 접수 결과)과 `coupon_issue_relay_total`(RELAY 모드의 MySQL 저장 확정 결과)을 한 그래프에 "접수"/"저장(RELAY)" 두 개의 라인으로 같이 표시. 동기든 비동기든 둘 다 쿠폰 발급이라 한 패널로 묶었지만, 같은 발급 건에 대해 두 지표가 순서대로 각각 한 번씩 찍히므로(RELAY 모드 한정) **두 값을 더하면 이중집계**가 됩니다 — 그래서 sum하지 않고 별도 라인으로만 분리해서 보여줍니다. 상단 `result_issue`(접수)/`result_relay`(저장) 변수로 각각 골라볼 수 있음 |
| 쿠폰 발급 실패 사유별 (접수/저장) | 위와 같은 이유로 `coupon_issue_total{result="fail"}`과 `coupon_issue_relay_total{result="fail"}`을 `reason`별로 분리해 한 패널에 같이 표시 |
| 쿠폰 상태 변경 현황 (전이별) | `coupon_state_change_total` 성공 건을 `from`→`to`별로 분리 |
| 쿠폰 상태 변경 실패 사유별 | 실패를 `reason`별로 분리 |
| 정합성 검증 현황 (체크/상태/대상) | `consistency_verification_total`을 `check`, `status`, `scope`별로 분리. 상단 `check`/`status`/`scope` 변수로 원하는 조합만 골라볼 수 있음 |

**대시보드 상단 변수(토글/필터)**

| 변수 | 의미 | 적용 패널 |
|---|---|---|
| `interval` | 집계 단위. `5s`부터 `1d`까지 드롭다운으로 선택(`1s`는 제외 — 3절 참고) — 초/분/시간 단위 그래프를 즉시 바꿔볼 수 있음 | 전체 |
| `result_issue` | 발급 접수 결과(`success`/`fail`) 다중 선택 | 쿠폰 발급 현황 |
| `result_relay` | RELAY 저장 결과(`success`/`fail`) 다중 선택 | 쿠폰 발급 현황 |
| `check` | 정합성 검증 항목 다중 선택 | 정합성 검증 현황 |
| `scope` | 정합성 검증 대상(`EVENT`/`AS_OF_RANGE`/`ALL`) 다중 선택 | 정합성 검증 현황 |
| `status` | 정합성 검증 상태(`PASS`/`FAIL`/`ERROR`) 다중 선택 | 정합성 검증 현황 |

패널마다 쓰는 변수를 분리해뒀기 때문에(`result_issue` vs `result_relay`) 한 패널의 필터를 바꿔도 다른 패널에는 영향이 없습니다. 반대로 `check`/`scope`/`status`는 정합성 검증 패널 하나만 참조하므로 이미 그 패널에만 국한됩니다. 여러 패널이 완전히 동일한 변수를 공유하게 만들면(예: 지금처럼 `result` 하나로 합쳐두면) 한쪽 필터가 다른 패널까지 같이 바뀌어버리니, 패널을 새로 추가할 때도 필터를 패널별로 독립시키고 싶다면 변수 이름을 패널마다 따로 만들어야 합니다.

각 패널은 `rate()` 대신 `increase(...[$interval])`을 써서 Y축이 "초당 건수"가 아니라 **선택한 집계 단위(interval) 동안의 실제 발급/검증 건수**로 표시됩니다. 예를 들어 `interval`을 `1h`로 바꾸면 시간당 발급 건수 그래프가 됩니다. 각 쿼리에는 `interval: "$interval"`이 지정돼 있어서, 그래프가 실제로 데이터를 찍는 간격(step)도 `interval` 값을 그대로 따라갑니다 — 이 지정이 없으면 Grafana가 Time range/패널 크기로 자동 계산한 step(기본 시간 범위 15분 기준 약 15s)을 대신 쓰기 때문에, 드롭다운에서 어떤 값을 골라도 그래프가 안 바뀌는 것처럼 보입니다.

`interval`을 지나치게 짧게 잡으면 그래프에 "No data"가 뜰 수 있습니다. `increase()`는 지정한 창 안에 원본 샘플이 최소 2개는 있어야 값을 계산하는데, `interval`이 Prometheus `scrape_interval`과 비슷하거나 더 짧으면 그 창 안에 샘플이 1개(또는 0개)만 들어가 계산 자체가 안 됩니다. `interval`은 최소 `scrape_interval`의 2~3배 이상으로 잡아야 안정적으로 값이 나옵니다 — 그래서 드롭다운 목록에서 `1s`는 아예 뺐습니다. 골라도 항상 "No data"만 뜨는 선택지를 목록에 남겨두는 건 혼란만 주기 때문입니다.

`result_issue`/`result_relay`/`check`/`scope`/`status` 변수는 Prometheus에 `label_values(...)`로 값을 물어보는 방식이라, 해당 라벨 값을 가진 데이터가 한 번도 수집되지 않았다면(예: RELAY 모드를 아직 켜지 않아 `coupon_issue_relay_total`이 없는 경우) 드롭다운에 값이 안 뜰 수 있습니다. 관련 API를 한 번 호출한 뒤 대시보드를 새로고침하면 값이 채워집니다.

"최근 N분/시간" 같은 조회 범위(Time range)는 `interval`(집계 단위)과 별개로, 화면 우측 상단 시계 아이콘을 누르면 나오는 목록(Last 5 minutes 등)뿐 아니라 그 옆 입력창에 `now-45m` `now-2h`처럼 직접 타이핑해서 원하는 범위를 바로 지정할 수 있습니다 — 이건 Grafana 자체 기능이라 대시보드 설정을 따로 건드릴 필요가 없습니다.

새로고침 주기는 화면 우측 상단 드롭다운에서 `1s`까지 낮출 수 있도록 설정해뒀지만, 실제로는 Prometheus `scrape_interval`보다 빠르게 당겨봐야 새 데이터가 없습니다 (3절 참고).

> [!NOTE]
> JVM 메모리, HTTP 트래픽, HikariCP(DB 커넥션) 같은 범용 지표를 보고 싶다면, **Dashboards** -> **New** -> **Import**에서 그라파나 공식 템플릿 `4701`(JVM Micrometer)을 추가로 Import해서 사용할 수 있습니다. 이 문서의 커스텀 대시보드와는 별개로 필요할 때만 추가하면 됩니다.

## 6. 프론트엔드(Ace_FE)에 임베드하기

`Ace_FE`의 **모니터링** 탭(`src/tabs/MonitoringTab.jsx`)이 5절의 Grafana 대시보드를 iframe으로 그대로 띄웁니다. 별도의 차트 구현 없이 Grafana 대시보드 자체를 화면 안에 넣는 방식이라, 5절에서 설명한 변수 드롭다운과 범례 클릭(시리즈 isolate)이 그대로 동작합니다.

이 방식이 동작하려면 Grafana를 다음 두 가지가 가능한 상태로 띄워야 합니다 (`docker-compose.yml`의 `grafana` 서비스에 이미 반영됨).
- **로그인 없이 조회 가능**: `GF_AUTH_ANONYMOUS_ENABLED=true` + `GF_AUTH_ANONYMOUS_ORG_ROLE=Viewer`. 익명 사용자는 Viewer 권한만 가지므로 대시보드를 보기만 할 수 있고 수정/삭제는 못합니다.
- **iframe 삽입 허용**: `GF_SECURITY_ALLOW_EMBEDDING=true`. 기본값(`false`)이면 Grafana가 자신을 iframe에 넣지 못하게 막습니다.

Ace_FE 쪽 설정:
1. `Ace_FE/.env`(또는 `.env.local`)에 `VITE_GRAFANA_URL`을 지정합니다. 기본값은 `http://localhost:3000`이라 로컬 docker-compose 그대로 쓰면 별도 설정 없이도 동작합니다.
2. `npm run dev`로 프론트를 띄운 뒤 좌측 메뉴의 **모니터링** 탭을 클릭하면 대시보드가 보입니다.

## 7. 트러블슈팅

**대시보드/데이터소스가 안 보여요**
- Grafana 컨테이너가 provisioning 설정을 반영하기 전(예: `docker/grafana/` 추가 전)에 이미 떠 있던 경우입니다. 1절의 `--force-recreate` 명령으로 컨테이너를 다시 만드세요.

**커스텀 지표(`coupon_issue_total` 등)가 Prometheus/Grafana에 안 보여요**
- Counter는 최소 한 번 이상 호출(증가)되기 전에는 `/actuator/prometheus`에 아예 노출되지 않습니다. 관련 API(쿠폰 발급/상태 변경/정합성 검증)를 한 번 호출한 뒤 다시 확인하세요.
- `management.endpoints.web.exposure.include`에 `prometheus`가 포함되어 있는지 `application.properties`에서 확인하세요.

**`ace-backend` Target이 DOWN이에요**
- 백엔드 서버가 8080 포트로 떠 있는지 확인하세요. `docker/prometheus/prometheus.yml`은 `host.docker.internal:8080`을 바라보므로, 서버를 컨테이너가 아니라 로컬(IDE)에서 직접 띄우는 구성을 전제로 합니다.

**Ace_FE 모니터링 탭에서 iframe이 비어 보이거나 콘솔에 `Refused to display ... in a frame` 에러가 떠요**
- Grafana 컨테이너가 `GF_SECURITY_ALLOW_EMBEDDING=true`를 반영하기 전에 이미 떠 있던 경우입니다. 1절의 `--force-recreate` 명령으로 재생성하세요.
- 브라우저 콘솔에 쿠키 관련 경고(SameSite)가 보이면, 프론트 개발 서버(예: `localhost:5173`)와 Grafana(`localhost:3000`)가 서로 다른 origin이라 발생하는 것입니다. 익명(Viewer) 접근이라 로그인 세션 자체는 필요 없지만, 브라우저 설정에 따라 여전히 막힐 수 있습니다 — 이 경우 크롬 기준 사이트 설정에서 서드파티 쿠키 차단을 해당 사이트에 한해 해제해보세요.
