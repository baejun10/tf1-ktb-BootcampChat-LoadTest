# KTB Chat 로컬 실행 및 부하 테스트 가이드

백엔드 개발자가 로컬 환경에서 KTB Chat 애플리케이션을 실행하고, Socket.IO 부하 테스트 스크립트(`loadtest`)를 활용해 성능/부하를 점검할 수 있도록 정리한 문서입니다.

## 1. 전체 구성 요약

- 모노레포 구조
  - `apps/frontend` : Next.js 기반 웹 프론트엔드 (기본 포트 `3000`)
  - `apps/backend` : Spring Boot 기반 REST API + Socket.IO 서버 (기본 포트 `5001`, WebSocket `5002`)
  - `loadtest` : Node.js 기반 커스텀 부하 테스트 스크립트
- 루트 스크립트
  - `npm run dev` : 프론트엔드 + 백엔드 동시 구동

부하 테스트 자체는 **백엔드 서버만 떠 있으면 실행 가능**하며, 프론트엔드는 선택 사항입니다.

---

## 2. 필수 사전 준비

### 2.1 공통

- Node.js 18 이상
- npm (또는 Node 설치 시 함께 포함된 npm)

### 2.2 백엔드용

- Java 21 (JDK)
- Docker + Docker Compose (MongoDB, Redis를 Testcontainers/Docker로 실행)

설치 확인:

```bash
java -version
docker --version
docker compose version
node -v
npm -v
```

백엔드 디렉터리에서 Java 환경 자동 설치도 가능합니다.

```bash
cd apps/backend
make setup-java     # SDKMAN + Java 21 설치
make verify-java    # Java/Maven 버전 확인
make verify-docker  # Docker 정상 동작 여부 확인
```

---

## 3. 레포 의존성 설치

루트에서 한 번만 실행하면 프론트/백엔드 dev 스크립트 구동에 필요한 `concurrently`가 설치됩니다.

```bash
cd /Users/devon.woo/Workspace/tf1-ktb-BootcampChat-LoadTest
npm install
```

부하 테스트용 의존성은 별도로 설치합니다(아래 6장 참조).

---

## 4. 백엔드 로컬 실행

부하 테스트 관점에서는 **백엔드 서버의 안정적 실행**이 가장 중요합니다.

### 4.1 환경 변수(.env) 준비

```bash
cd apps/backend
make setup-env   # .env가 없으면 .env.template 복사 후 키 자동 생성
```

필수/주요 환경 변수 (`apps/backend/README.md` 기준):

| 변수 | 기본값/설명 |
|------|-------------|
| `MONGO_URI` | 예: `mongodb://localhost:27017/bootcamp-chat` |
| `REDIS_HOST` | 예: `localhost` |
| `REDIS_PORT` | 예: `6379` |
| `PORT` | HTTP API 포트, 기본 `5001` (`server.port`) |
| `WS_PORT` | Socket.IO 포트, 기본 `5002` |
| `JWT_SECRET` | 필수, JWT 서명 키 |
| `ENCRYPTION_KEY` | 필수, 64자리 HEX |
| `ENCRYPTION_SALT` | 필수, 32자리 HEX |
| `OPENAI_API_KEY` | (선택) OpenAI 연동 시 사용 |

`make setup-env`를 사용하면 위 필수 키들은 자동 생성되며, Mongo/Redis는 Testcontainers나 Docker Compose로 자동 구동됩니다.

### 4.2 백엔드 서버 실행

```bash
cd apps/backend
make dev
```

- `dev` 타깃은:
  - `.env` 존재 여부 확인 및 생성 (`make setup-env`)
  - Docker 환경 확인 (`make verify-docker`)
  - Maven Wrapper(`./mvnw`)를 사용하여 `dev` 프로파일로 Spring Boot 실행
  - MongoDB/Redis는 Testcontainers 또는 `docker-compose` 설정에 따라 자동 기동

정상 실행 후 다음을 확인할 수 있습니다.

- REST API: `http://localhost:5001`
- Swagger UI: `http://localhost:5001/api/swagger-ui.html`
- Socket.IO 서버: `http://localhost:5002` (부하 테스트 스크립트가 이 포트로 연결)

---

## 5. 프론트엔드 로컬 실행 (선택)

부하 테스트 자체에는 필수는 아니지만, UI를 보면서 상태를 확인하고 싶다면 함께 실행할 수 있습니다.

### 5.1 프론트엔드 의존성 설치

```bash
cd apps/frontend
npm install
```

### 5.2 프론트엔드 dev 서버 실행

```bash
cd apps/frontend
npm run dev
```

- 기본 포트: `http://localhost:3000`
- 백엔드를 `http://localhost:5001` / `5002` 기준으로 호출하도록 구성되어 있습니다.

### 5.3 루트에서 프론트 + 백엔드 동시 실행 (편의)

루트에서 다음을 실행하면 프론트/백엔드 모두 dev 모드로 기동됩니다.

```bash
cd /Users/devon.woo/Workspace/tf1-ktb-BootcampChat-LoadTest
npm run dev
```

- 내부 동작
  - `apps/frontend`에서 `npm run dev`
  - `apps/backend`에서 `make dev`

---

## 6. 부하 테스트(loadtest) 환경 준비

### 6.1 의존성 설치

```bash
cd /Users/devon.woo/Workspace/tf1-ktb-BootcampChat-LoadTest/loadtest
npm install
```

### 6.2 기본 전제

- 백엔드 서버가 **이미 기동**되어 있어야 합니다.
  - REST API: 기본 `http://localhost:5001` 또는 README 기준 `http://localhost:3000`을 사용하는 부분이 있으므로 스크립트 옵션으로 맞춰줄 수 있습니다.
  - Socket.IO: 기본 `http://localhost:5002`
- 부하 테스트 스크립트는 JWT + Session 인증 플로우를 자동으로 처리하고, 필요 시 테스트 유저를 자동 생성합니다.

---

## 7. 테스트 유저 사전 생성 (선택)

부하 테스트 시 매번 유저를 생성해도 되지만, 미리 계정을 만들어두면 테스트 속도가 조금 더 안정적입니다.

```bash
cd /Users/devon.woo/Workspace/tf1-ktb-BootcampChat-LoadTest/loadtest

# npm 스크립트 사용 (기본 설정)
npm run create-users

# 직접 스크립트 실행 + 커스텀 옵션
node create-test-users.js --count=1000 --api-url=http://localhost:5001
```

- `--count` : 생성할 유저 수
- `--api-url` : 백엔드 REST API URL (포트/도메인 조정 가능)

---

## 8. 부하 테스트 실행 예시

### 8.1 Batch Load Test (`load-test.js`)

동일 채팅방에 많은 사용자가 동시에 접속/메시지 전송하는 피크 부하 시나리오입니다.

```bash
cd /Users/devon.woo/Workspace/tf1-ktb-BootcampChat-LoadTest/loadtest

# 가벼운 테스트 (50명, 10명씩 배치, 1초 간격)
npm run test:light

# 중간 테스트 (200명, 20명씩 배치, 0.7초 간격)
npm run test:medium

# 무거운 테스트 (1000명, 20명씩 배치, 0.7초 간격)
npm run test:heavy
```

필요 시 직접 옵션을 넘겨 커스텀 실행도 가능합니다.

```bash
node load-test.js \
  --users=500 \
  --batch-size=25 \
  --batch-delay=1000 \
  --messages=50 \
  --api-url=http://localhost:5001 \
  --socket-url=http://localhost:5002
```

주요 옵션(일부):

- `--users` (`-u`) : 총 유저 수
- `--batch-size` (`-b`) : 한 번에 접속할 유저 수
- `--batch-delay` : 배치 간 딜레이(ms)
- `--messages` (`-m`) : 유저당 전송할 메시지 수
- `--api-url` : REST API URL
- `--socket-url` : Socket.IO URL

### 8.2 Ramp-Up Load Test (`ramp-up-test.js`)

시간이 지날수록 **사용자 수와 방 수가 점진적으로 증가하고**, 일정 시간 동안 최대 부하를 유지하는 시나리오입니다.

```bash
cd /Users/devon.woo/Workspace/tf1-ktb-BootcampChat-LoadTest/loadtest

# 기본 설정 (예: 최대 500명, 3분 유지)
npm run test:rampup

# 작은 규모 (200명)
npm run test:rampup:small

# 대규모 (1000명, 빠른 증가)
npm run test:rampup:large
```

커스텀 예시:

```bash
node ramp-up-test.js \
  --max-users=300 \
  --min-users-per-second=5 \
  --max-users-per-second=10 \
  --sustain-duration=300 \
  --api-url=http://localhost:5001 \
  --socket-url=http://localhost:5002
```

주요 옵션(일부):

- `--max-users` (`-u`) : 최대 동시 접속 사용자 수
- `--min-users-per-second` / `--max-users-per-second` : 초당 증가 사용자 범위
- `--sustain-duration` (`-s`) : 최대 부하 유지 시간(초)
- `--message-interval-min` / `--message-interval-max` : 메시지 전송 간격 범위(ms)
- `--api-url`, `--socket-url` : 백엔드 URL

---

## 9. 부하 테스트 시 모니터링 팁

### 9.1 백엔드 애플리케이션 로그

```bash
cd apps/backend
tail -f target/*.log  # 실제 로그 파일 위치에 맞게 조정
```

또는 IDE에서 `ChatAppApplication` 실행 로그를 직접 확인합니다.

### 9.2 Prometheus + Grafana 스택 실행 (선택)

백엔드에는 Prometheus/Grafana 기반 모니터링 스택이 포함되어 있습니다.

```bash
cd apps/backend
make o11y-up
```

기본 접속:

- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000` (기본 계정 `admin/admin`)

중지:

```bash
cd apps/backend
make o11y-down
```

---

## 10. 자주 발생하는 이슈 체크리스트

- 백엔드가 5001/5002 포트에서 정상 기동되었는지
  - `curl http://localhost:5001/actuator/health` 등으로 확인
- Docker 데몬이 실행 중인지 (`docker info`)
- `.env`에 필수 키(JWT/ENCRYPTION/MONGO/REDIS)가 설정되어 있는지
- `loadtest` 스크립트에서 사용하는 `--api-url`, `--socket-url`이 실제 백엔드 포트와 일치하는지
- 너무 높은 동시 접속 수를 초기부터 넣고 있지 않은지 (먼저 `test:light`로 연습 후 점진적으로 증가)

위 절차대로 설정하면, 백엔드 개발자가 로컬 환경에서 **Spring Boot 백엔드 + Socket.IO 서버를 기동**하고, `loadtest` 디렉터리의 스크립트로 **피크 부하 / 점진적 부하 / 장시간 안정성 테스트**를 모두 수행할 수 있습니다.

