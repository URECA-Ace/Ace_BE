# 📊 모니터링 환경 (Prometheus + Grafana) 실행 가이드

본 문서는 로컬 환경에서 백엔드 서버의 상태를 모니터링하기 위한 가이드입니다.

## 1. 사전 준비 (인프라 실행)
모니터링을 위해서는 백엔드가 사용하는 인프라(MySQL, Redis, Kafka)와 함께 Prometheus, Grafana가 실행되어야 합니다.

```bash
# 백그라운드로 전체 컨테이너 실행
docker-compose up -d
```
> [!NOTE]  
> Podman 사용자는 `podman-compose up -d`를 사용하시면 됩니다. 처음 실행 시 이미지를 다운로드하느라 시간이 소요될 수 있습니다.

## 2. 백엔드 서버 실행
IDE(IntelliJ 등)에서 `AceBeApplication`을 실행합니다. 
로컬 DB 연결이 필요한 경우 `src/main/resources/application.properties`의 설정이 알맞게 되어 있는지 확인하세요.

## 3. 접속 테스트
서버가 기동되면 아래 링크들에 순서대로 접속하여 상태를 확인합니다.

1. **Spring Boot Actuator 지표 노출 확인**
   - [http://localhost:8080/actuator/prometheus](http://localhost:8080/actuator/prometheus)
   - 접속 시 `jvm_memory_used_bytes` 등의 텍스트가 빽빽하게 출력되면 정상입니다.
2. **Prometheus 수집 확인**
   - [http://localhost:9090](http://localhost:9090) 접속
   - 상단 메뉴 **Status** -> **Targets** 클릭
   - `ace-backend` 항목의 State가 초록색 **UP**인지 확인합니다.

## 4. Grafana 대시보드 연동 가이드
그라파나에서 프로메테우스 데이터를 시각화하기 위한 초기 1회 설정입니다.

### 4-1. Data Source (프로메테우스) 연결
1. [http://localhost:3000](http://localhost:3000) 접속 (초기 계정: `admin` / `admin`)
2. 좌측 메뉴(≡) -> **Connections** -> **Data sources** 이동
3. **[Add new data source]** 클릭 후 **Prometheus** 선택
4. Connection URL에 `http://ace-prometheus:9090` 입력 
   *(도커 네트워크 내부 통신이므로 localhost가 아닌 컨테이너명을 사용합니다)*
5. 맨 아래 **[Save & test]** 클릭 (초록색 성공 알림창이 뜨면 성공)

### 4-2. 대시보드 템플릿 가져오기 (Import)
1. 좌측 메뉴(≡) -> **Dashboards** 이동
2. 우측 상단 **[New]** -> **[Import]** 클릭
3. "Import via grafana.com" 란에 **`4701`** 입력 후 **[Load]** 클릭 (JVM Micrometer 범용 대시보드)
   > *(참고: 다른 테마를 원하시면 `11378`, `19004` 등도 사용 가능합니다)*
4. 가장 하단 `Prometheus` 드롭다운에서 방금 생성한 데이터 소스를 선택하고 **[Import]** 클릭

이제 모니터링 대시보드를 통해 실시간 HTTP 트래픽, JVM 메모리, HikariCP(DB 커넥션) 등의 지표를 한눈에 확인할 수 있습니다!
