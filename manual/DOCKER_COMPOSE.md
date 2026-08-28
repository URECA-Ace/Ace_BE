# 로컬 개발 환경 (Docker Compose)

팀원 전원이 동일한 버전의 MySQL / Redis 위에서 개발하기 위한 문서입니다.
애플리케이션(Spring Boot)은 컨테이너로 띄우지 않고, **인프라만 컨테이너로 띄운 뒤 IDE에서 실행**하는 구성입니다.
애플리케이션은 모두 만들어진 후, 3개의 인스턴스를 도커로 띄울 예정입니다.

## 구성

| 서비스 | 이미지 | 컨테이너 이름 | 호스트 포트 | 비고 |
| --- | --- | --- | --- | --- |
| MySQL | `mysql:8.4` | `ace-mysql` | **3307** | utf8mb4, 타임존 `Asia/Seoul` |
| Redis | `redis:8.2` | `ace-redis` | 6379 | AOF 활성화 |

두 서비스는 `ace-net` 브리지 네트워크로 묶여 있고, 데이터는 명명된 볼륨(`ace_mysql-data`, `ace_redis-data`)에 저장되므로 컨테이너를 지워도 유지됩니다.

## 사전 준비

- Docker Desktop 설치 후 **실행 중**이어야 합니다. 꺼져 있으면 아래와 같은 에러가 납니다.
  ```
  failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine
  ```
- 3307 / 6379 포트가 비어 있어야 합니다.

> **MySQL 포트가 3307인 이유**
> Workbench와 함께 설치되는 로컬 MySQL 서버가 3306을 쓰고 있는 경우가 많아, 충돌을 피하려고 호스트 포트를 3307로 잡았습니다.
> 컨테이너 내부는 그대로 3306이므로 컨테이너끼리 통신할 때는 `mysql:3306`을 씁니다. 호스트(IDE, Workbench)에서 붙을 때만 3307입니다.
> 덕분에 로컬 MySQL을 끄지 않고 그대로 둬도 됩니다.

## 명령어는 어디서 입력하나

아래 모든 `docker compose ...` 명령은 **`docker-compose.yml`이 있는 프로젝트 루트에서** 실행해야 합니다. 터미널 종류(PowerShell, cmd, Git Bash 등)는 상관없고 위치만 맞으면 됩니다.

가장 편한 방법은 **IntelliJ 하단 Terminal 탭**(`Alt` + `F12`)입니다. 프로젝트 루트에서 열리므로 바로 입력하면 됩니다.

별도 터미널을 쓴다면 프로젝트 폴더로 이동한 뒤 실행하세요.

```powershell
cd C:\Yureca_Ace\Ace_BE
docker compose up -d
```

위치가 맞는지는 `docker-compose.yml`이 보이는지로 확인합니다.

```powershell
dir docker-compose.yml
```

엉뚱한 폴더에서 실행하면 아래 에러가 납니다. 이때는 `cd`로 프로젝트 루트로 이동하세요.

```
no configuration file provided: not found
```

> Docker Desktop 앱에서도 컨테이너 상태와 로그를 GUI로 볼 수 있지만, 기동/중지는 위 명령어로 하는 것을 기준으로 합니다.

## 빠른 시작

```bash
# 1. 기동 (최초 실행 시 이미지 다운로드로 몇 분 걸립니다)
docker compose up -d

# 2. 상태 확인 - 두 서비스 모두 STATUS 가 (healthy) 여야 정상
docker compose ps
```

`(health: starting)` 상태라면 아직 기동 중이므로 20~30초 뒤 다시 확인하세요.

기동 확인:

```bash
docker compose exec mysql mysql -uroot -p1234 -e "select version()"
docker compose exec redis redis-cli ping
```

## 접속 정보

| 항목 | 값 |
| --- | --- |
| MySQL | `localhost:3307` |
| MySQL 계정 | `root` / `1234` |
| MySQL 데이터베이스 | `ace` |
| Redis | `localhost:6379` (비밀번호 없음) |

`src/main/resources/application.properties`에서 사용할 값입니다. (이 파일은 `.gitignore` 대상이라 각자 로컬에서 관리합니다.)

```properties
spring.datasource.url=jdbc:mysql://localhost:3307/ace?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
spring.datasource.username=root
spring.datasource.password=1234

spring.data.redis.host=localhost
spring.data.redis.port=6379
```

### MySQL Workbench 연결

기존 로컬 MySQL 접속과 별개로 새 커넥션을 하나 추가하면 됩니다.

1. 시작 화면에서 **MySQL Connections** 옆 `+` 클릭
2. 아래 값 입력 후 **Test Connection** 으로 확인

| 항목 | 값 |
| --- | --- |
| Connection Name | `ace-docker` (자유롭게) |
| Hostname | `127.0.0.1` |
| Port | `3307` |
| Username | `root` |
| Password | `1234` |
| Default Schema | `ace` |

포트만 3307로 다를 뿐 나머지는 평소와 같습니다. 기존 3306 커넥션은 그대로 두고 병행해서 쓰면 됩니다.

## 애플리케이션에서 사용하기

### 1. application.properties 작성

`src/main/resources/application.properties`는 `.gitignore` 대상이라 저장소에 없습니다. **각자 만들어야 합니다.** `application-example.properties`를 복사한 뒤 아래 내용으로 채우세요.

```properties
spring.application.name=ace-be

# --- MySQL (컨테이너 포트 3307) ---
spring.datasource.url=jdbc:mysql://localhost:3307/ace?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
spring.datasource.username=root
spring.datasource.password=1234
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver

spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true

# --- Redis ---
spring.data.redis.host=localhost
spring.data.redis.port=6379
```

컨테이너가 떠 있는 상태에서 애플리케이션을 실행하면 별도 코드 없이 연결됩니다. 연결에 실패하면 기동 로그에 바로 에러가 찍힙니다.

### 2. MySQL - JPA

`ddl-auto=update` 설정이라 엔티티를 만들면 테이블이 자동 생성됩니다.

```java
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
}

public interface MemberRepository extends JpaRepository<Member, Long> {
}
```

`memberRepository.save(...)` 로 저장한 뒤 실제로 들어갔는지 확인:

```bash
docker compose exec mysql mysql -uroot -p1234 ace -e "show tables; select * from member"
```

Workbench의 `ace-docker`(3307) 커넥션에서도 동일하게 보입니다.

### 3. Redis

`spring-boot-starter-data-redis`가 `StringRedisTemplate`을 자동 등록하므로 주입만 하면 됩니다.

```java
@Service
@RequiredArgsConstructor
public class CacheService {

    private final StringRedisTemplate redisTemplate;

    public void save(String key, String value) {
        redisTemplate.opsForValue().set(key, value, Duration.ofMinutes(10)); // TTL 10분
    }

    public String find(String key) {
        return redisTemplate.opsForValue().get(key);
    }
}
```

저장된 키 확인:

```bash
docker compose exec redis redis-cli keys "*"
docker compose exec redis redis-cli get 원하는키
docker compose exec redis redis-cli ttl 원하는키    # 남은 만료 시간(초)
```

## 자주 쓰는 명령어

```bash
docker compose up -d              # 기동
docker compose stop               # 중지 (컨테이너·데이터 유지)
docker compose start               # 중지한 것 다시 시작
docker compose restart mysql       # 특정 서비스만 재시작
docker compose down               # 컨테이너 삭제 (볼륨 데이터는 유지)
docker compose down -v            # 컨테이너 + 볼륨(데이터) 전부 삭제

docker compose ps                 # 상태 확인
docker compose logs -f mysql      # 로그 실시간 확인
docker compose pull               # 이미지 태그 갱신분 받아오기
```

컨테이너 안으로 들어가기:

```bash
docker compose exec mysql mysql -uroot -p1234 ace   # MySQL 콘솔
docker compose exec redis redis-cli                 # Redis CLI
```

## 데이터 저장 (명명된 볼륨)

`docker-compose.yml` 맨 아래를 보면 이름만 있고 값이 비어 있습니다.

```yaml
volumes:
  mysql-data:
  redis-data:
```

**이건 미완성이 아니라 정상입니다.** 값이 비어 있다는 건 "기본 옵션으로 Docker가 알아서 만들어 달라"는 뜻이며, 직접 채워 넣을 내용은 없습니다. 신경 쓰지 않아도 됩니다.

### 언제, 어떻게 만들어지나

`docker compose up -d`를 처음 실행할 때 Docker가 자동으로 생성합니다. 실제 이름에는 프로젝트 이름(`docker-compose.yml`의 `name: ace`)이 접두사로 붙습니다.

```bash
docker volume ls
# DRIVER    VOLUME NAME
# local     ace_mysql-data
# local     ace_redis-data
```

### 어떻게 채워지나

우리가 파일을 넣는 게 아니라, **컨테이너가 동작하면서 스스로 씁니다.** 각 서비스의 `volumes:` 항목이 "컨테이너 안의 이 경로에 쓴 내용을 저 볼륨에 저장하라"는 연결입니다.

| 볼륨 | 컨테이너 내부 경로 | 저장되는 것 |
| --- | --- | --- |
| `mysql-data` | `/var/lib/mysql` | 데이터베이스 파일 (테이블, 레코드) |
| `redis-data` | `/data` | AOF/RDB 스냅샷 |

즉 JPA가 테이블을 만들거나 데이터를 INSERT하는 순간 `mysql-data`에 쌓입니다. 아직 아무것도 저장한 게 없다면 MySQL 시스템 테이블과 빈 `ace` 데이터베이스만 들어 있는 상태입니다.

이 덕분에 `docker compose down` 으로 컨테이너를 지웠다가 다시 `up` 해도 이전 데이터가 그대로 남습니다.

### 내용 확인하기

볼륨은 Docker가 관리하는 영역이라 탐색기로 직접 열어보는 용도가 아닙니다. 확인은 컨테이너를 통해서 합니다.

```bash
# 실제 데이터가 쌓였는지 (MySQL 데이터 디렉터리 확인)
docker compose exec mysql ls -al /var/lib/mysql

# 테이블 목록
docker compose exec mysql mysql -uroot -p1234 -e "show tables" ace

# 볼륨 상세 정보 (마운트 경로, 생성 시각 등)
docker volume inspect ace_mysql-data
```

### 명명된 볼륨 vs 바인드 마운트

이 프로젝트는 두 방식을 함께 씁니다. 차이를 알아두면 헷갈리지 않습니다.

| | 명명된 볼륨 | 바인드 마운트 |
| --- | --- | --- |
| 예시 | `mysql-data:/var/lib/mysql` | `./docker/mysql/init:/docker-entrypoint-initdb.d` |
| 위치 | Docker가 관리 (경로 신경 쓸 필요 없음) | 프로젝트 폴더 안, 우리가 직접 파일을 넣음 |
| 최상단 `volumes:`에 선언 | 필요함 | 필요 없음 |
| 용도 | 컨테이너가 쓰는 데이터 보관 | 우리가 컨테이너에 파일 전달 |

**우리가 직접 채워 넣는 건 `docker/mysql/init/` 쪽뿐입니다.** (아래 "MySQL 초기 스키마" 참고)

### 초기화

DB 스키마가 꼬였거나 처음부터 다시 시작하고 싶을 때만 지우면 됩니다.

```bash
docker compose down -v          # 전체 볼륨 삭제
docker volume rm ace_mysql-data # 특정 볼륨만 삭제 (컨테이너를 먼저 내려야 함)
```

## MySQL 초기 스키마

`docker/mysql/init/` 에 `.sql` 파일을 넣어두면 **볼륨이 비어 있는 최초 기동 시** 파일명 사전순으로 자동 실행됩니다.

주의: 이미 한 번 기동한 뒤에 SQL을 추가하면 실행되지 않습니다. 다시 태우려면 볼륨을 지우고 재기동해야 합니다.

```bash
docker compose down -v
docker compose up -d
```

## 설정 덮어쓰기

포트나 비밀번호를 개인적으로 바꿔야 하면 프로젝트 루트에 `.env` 파일을 만드세요. compose가 `${VAR:-기본값}` 형태로 읽으므로 정의한 값만 덮어써집니다. `.env`는 `.gitignore`에 등록되어 있어 커밋되지 않습니다.

```dotenv
MYSQL_PORT=13307
MYSQL_ROOT_PASSWORD=mypassword
REDIS_PORT=16379
```

사용 가능한 변수: `MYSQL_PORT`, `MYSQL_ROOT_PASSWORD`, `MYSQL_DATABASE`, `REDIS_PORT`

포트를 바꿨다면 `application.properties`도 같이 맞춰야 합니다.

## 트러블슈팅

**포트가 이미 사용 중 (`port is already allocated`)**
해당 포트를 다른 프로그램이 쓰고 있는 경우입니다. 위 `.env`로 호스트 포트를 바꾸거나, 아래 명령으로 점유 중인 프로세스를 확인하세요.

```powershell
netstat -ano | findstr :3307
```

**Workbench에서 3306으로 접속했는데 `ace` DB가 안 보임**
로컬에 설치된 MySQL에 붙은 것입니다. 컨테이너는 **3307**이므로 포트를 확인하세요.

**MySQL이 healthy가 안 됨**
`docker compose logs mysql`로 확인합니다. 이전에 다른 비밀번호로 초기화된 볼륨이 남아 있으면 새 비밀번호가 적용되지 않습니다. 이때는 `docker compose down -v` 후 재기동하세요.

**Spring에서 DB 연결이 안 됨**
`docker compose ps`로 `(healthy)` 여부를 먼저 확인하세요. 컨테이너가 뜬 직후에는 MySQL이 아직 초기화 중이라 연결이 거부될 수 있습니다.

**설정을 바꿨는데 반영이 안 됨**
`docker-compose.yml`이나 `.env`를 수정한 뒤에는 `docker compose up -d`를 다시 실행해야 컨테이너가 새 설정으로 재생성됩니다.

## 참고

- 버전을 올릴 때는 `docker-compose.yml`의 이미지 태그를 수정하고 팀에 공유합니다. 팀원은 `docker compose pull && docker compose up -d`로 반영합니다.
- 이 구성은 로컬 개발 전용입니다. 비밀번호가 평문으로 들어 있으므로 운영 환경에 그대로 사용하지 마세요.
