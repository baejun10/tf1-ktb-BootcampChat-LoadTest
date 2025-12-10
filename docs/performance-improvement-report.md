# Bootcamp Chat Load Test – Realtime Stack 개선 보고서

## 1. 개요

부하 테스트 환경에서 Socket.IO 기반 채팅 서버는 JSON 페이로드를 중심으로 동작하며, 세션 검증·레이트 리밋·금칙어 검사·AI 멘션 처리까지 단일 워커에서 순차 실행된다. 이 문서는 `ChatMessageHandler`, `LocalFileService`, `RateLimitService`, `RoomService`, `WebMvcConfig`를 기반으로 파악한 성능 병목과 개선 방향을 정리한다.

## 2. 실시간 메시지 파이프라인

### 2.1 Room/참가자 조회 캐싱
- `ChatMessageHandler`는 메시지마다 `RoomRepository.findById`로 참여자 목록을 받아 권한을 검증한다.
- 대량 트래픽에서는 DB 라운드트립이 급증하므로, Socket.IO 접속 시점에 방 정보와 참가자 해시를 로컬/분산 캐시에 저장하고 메시지 처리 중에는 캐시를 참조하도록 리팩터링한다.

### 2.2 파일 메타데이터 재사용
- 파일 메시지를 처리할 때 이미 로드한 `File` 엔터티를 응답 생성 시 버리고 다시 `fileRepository`를 조회한다.
- `handleFileMessage`에서 `FileResponse`까지 만들어 `MessageResponse`에 직접 주입하면 Mongo 조회 1회를 제거하고 파일 메시지 지연을 줄인다.

### 2.3 AI 멘션 비동기화
- 현재 AI 호출은 메시지 저장 직후 동기적으로 실행된다.
- 메시지 브로드캐스트 이후 비동기 큐(예: Kafka, Redis Stream)로 offload하여 Socket.IO 워커가 즉시 다음 메시지를 처리할 수 있도록 한다.

### 2.4 세션·레이트리밋 비용 완화
- 매 메시지마다 세션 검증과 레이트리밋을 수행해 CPU와 DB/캐시 부하가 크다.
- Socket.IO handshake 단계에서 강한 검증을 실행하고, 이후에는 토큰 만료 주기에 맞춰 샘플링 검증하거나, 사용자별 burst counter만 빠르게 체크하도록 구조를 단순화한다.

## 3. RateLimit 저장소 개선

### 3.1 원자적 카운터 연산
- `RateLimitService`는 `find → setCount → save` 패턴으로 경쟁 상태와 write 증폭이 발생한다.
- Mongo `$inc` + upsert, Redis INCR/EXPIRE 같은 원자 연산을 도입하여 동일 윈도우 내 다중 요청도 하나의 round-trip으로 처리한다.

### 3.2 TTL 관리 위임
- 현재 만료 계산을 애플리케이션이 직접 수행해 만료된 문서가 쌓인다.
- Mongo TTL 인덱스나 Redis key 만료를 활용해 자동 정리되도록 하면 운영 부담이 줄고 저장소 크기를 안정적으로 유지할 수 있다.

### 3.3 글로벌 키 스페이스
- 인스턴스 호스트명을 clientId에 붙이면 노드 수가 증가할수록 키가 분산돼 제한 효과가 약해진다.
- 공통 저장소(예: Redis Cluster)에 호스트 프리픽스 없이 사용자 ID를 키로 사용해 어느 인스턴스에서든 동일한 제한을 적용한다.

## 4. Room/참가자 데이터 접근

### 4.1 참가자 로딩 최적화
- `RoomService.mapToRoomResponse`는 참가자 ID마다 `userRepository.findById`를 호출하는 N+1 구조다.
- Mongo aggregation `$lookup` 또는 `participant` 요약 데이터를 `Room` 문서에 캐싱하여 방 목록 페이징 시 일괄 로딩한다.

### 4.2 최근 메시지 카운트
- 방별 `messageRepository.countRecentMessagesByRoomId` 호출이 페이지 크기에 비례해 증가한다.
- Aggregation 파이프라인으로 여러 방의 최근 메시지 수를 한 번에 구하거나, Redis Sorted Set에 시간 기반 카운트를 유지해 읽기 부하를 제거한다.

### 4.3 세션과 방 정보 동기화
- Socket.IO 세션/토큰 서비스와 `Room` 데이터를 연동해 사용자가 속한 방 목록을 세션 메타데이터에 캐시하면 메시지 처리 시 DB 조회 없이 권한을 검증할 수 있다.

## 5. 파일 서비스 및 정적 리소스

### 5.1 다운로드 권한 조회 축소
- `LocalFileService.loadFileAsResource`는 `File → Message → Room`을 순차 조회한다.
- 파일 문서에 `roomId`/`participantSnapshot`을 저장하거나 Mongo aggregation으로 조인을 수행해 다운로드당 1회 조회로 줄인다.

### 5.2 파일 삭제 권한 확장
- 업로더만 삭제 가능한 정책은 운영 중 문제 발생 시 병목이 된다.
- 룸 관리자/운영자 권한을 메타데이터에 포함해 비상 시 삭제가 가능하도록 한다.

### 5.3 스토리지 외부화
- `WebMvcConfig`는 로컬 디스크를 `/api/uploads/**`로 직접 노출한다.
- CDN/S3와 같은 외부 스토리지로 업로드를 이전하고, 서버는 서명 URL 발급 및 메타데이터만 담당하도록 분리하면 애플리케이션 노드의 디스크/IO 부담을 제거할 수 있다.

## 6. 우선순위 제안

1. **메시지 파이프라인 캐싱/비동기화**: 방 정보 캐시, 파일 메타 재사용, AI offload → 메시지당 DB 호출 감소와 처리 시간 단축.
2. **RateLimit 저장소 리팩터링**: 원자 연산 + TTL 인덱스 → 부하 시 스로틀링 정확도와 안정성 확보.
3. **Room/Message 집계 최적화**: Aggregation/캐시 도입으로 목록 API 성능 개선.
4. **파일 스토리지 전략 전환**: 외부 스토리지와 권한 모델 확장으로 장기적 확장성 확보.

각 항목은 부하 테스트 시나리오로 효과를 수치화해가며 점진적으로 적용하는 것을 권장한다.
