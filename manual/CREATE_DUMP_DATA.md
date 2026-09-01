# 로컬 DB 복원 가이드

400만 건 규모의 더미 데이터(유저 100만 / 쿠폰 발급 이력 300만 등)가 미리 채워진 MySQL 볼륨을
tar 파일로 공유합니다. 아래 순서대로 실행하면 본인 로컬에도 동일한 데이터가 들어간 DB가 생깁니다.

Windows(CMD/PowerShell), Mac, Linux 상관없이 **아래 명령어를 그대로 복사해서 쓰면 됩니다.**
모든 작업이 Docker 컨테이너 내부에서 실행되기 때문에 OS별로 명령어가 달라지지 않습니다.

redis는 이 작업과 무관하며 전혀 영향받지 않습니다. mysql만 초기화/복원됩니다.

---

## 0. 사전 준비

1. 공유받은 백업 파일(`ace_mysql_volume_YYYYMMDD.tar.gz`)을 다운로드합니다.
    - 공유 링크: `https://drive.google.com/file/d/1qKftWmElaMkwk-jBjx5Vi73Ves2-yH92/view?usp=sharing`
2. 다운로드한 파일을 프로젝트 루트의 `dump/` 폴더 안에 넣습니다.
    - 폴더가 없다면 새로 만들어주세요.
    - 최종 경로 예시: `Ace_BE/dump/ace_mysql_volume_20260812.tar.gz`

```
Ace_BE/
 ├─ docker-compose.yml
 ├─ dump/
 │   └─ ace_mysql_volume_20260812.tar.gz   ← 이렇게 위치
 └─ ...
```

> dump 폴더에 tar.gz 파일이 여러 개 있으면 **가장 최근에 받은 파일**이 자동으로 선택됩니다.

---

## 1. 복원 실행

프로젝트 루트(docker-compose.yml이 있는 위치)에서, 터미널(Mac/Linux) 또는 CMD/PowerShell(Windows)을
열고 아래 6줄을 **순서대로** 실행합니다.

```bash
docker compose stop mysql
docker compose rm -f mysql
docker volume rm ace_mysql-data
docker compose up -d mysql
docker compose --profile restore run --rm restore
docker compose restart mysql
```

### 각 줄이 하는 일

| 명령어 | 하는 일 |
|---|---|
| `stop mysql` | mysql 컨테이너만 정지 (redis는 안 건드림) |
| `rm -f mysql` | mysql 컨테이너 삭제 |
| `volume rm ace_mysql-data` | 기존 mysql 데이터 볼륨 완전 삭제 (깨끗한 상태로 시작하기 위함) |
| `up -d mysql` | 빈 볼륨으로 mysql을 한 번 기동 (내부 초기 구조 생성) |
| `--profile restore run --rm restore` | dump 폴더의 tar.gz를 찾아 mysql 볼륨에 압축 해제 |
| `restart mysql` | mysql이 새로 들어온 데이터 파일을 다시 읽도록 재시작 |

---

## 2. 정상적으로 복원됐는지 확인

```bash
docker exec -it ace-mysql mysql -uroot -p1234 -e "SELECT COUNT(*) FROM ace.coupon_issue;"
```

아래처럼 `3000000`이 나오면 정상입니다.

```
+----------+
| COUNT(*) |
+----------+
|  3000000 |
+----------+
```

추가로 다른 테이블도 확인하고 싶다면:

```bash
docker exec -it ace-mysql mysql -uroot -p1234 -e "SELECT COUNT(*) FROM ace.user;"
docker exec -it ace-mysql mysql -uroot -p1234 -e "SELECT COUNT(*) FROM ace.coupon;"
docker exec -it ace-mysql mysql -uroot -p1234 -e "SELECT COUNT(*) FROM ace.coupon_event;"
```

| 테이블 | 예상 건수 |
|---|---|
| user | 1,000,000 |
| coupon | 1 |
| coupon_event | 30 |
| coupon_issue | 3,000,000 |
| coupon_history | 0 (의도적으로 비워둠) |
