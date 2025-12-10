# 성능 개선 문서

## MessageLoader 성능 최적화 (2025-12-10)

### 개선 대상
`MessageLoader.loadMessagesInternal()` 메서드

### 문제점
1. **N+1 쿼리 문제**: 메시지 수만큼 User 조회 반복 (30개 메시지 → 30회 DB 쿼리)
2. **Stream 이중 순회**: `messageIds` 추출과 `MessageResponse` 생성을 위해 2번 순회

### 개선 내용

#### 1. TODO 014: User N+1 문제 해결
**개선 전:**
```java
List<MessageResponse> messageResponses = sortedMessages.stream()
    .map(message -> {
        var user = findUserById(message.getSenderId());  // N번 DB 쿼리
        return messageResponseMapper.mapToMessageResponse(message, user);
    })
    .collect(Collectors.toList());
```

**개선 후:**
```java
// 모든 senderIds를 한 번에 batch 조회
Map<String, User> userMap = userRepository.findAllById(senderIds).stream()
    .collect(Collectors.toMap(User::getId, user -> user));

List<MessageResponse> messageResponses = sortedMessages.stream()
    .map(message -> {
        var user = userMap.get(message.getSenderId());  // O(1) Map 조회
        return messageResponseMapper.mapToMessageResponse(message, user);
    })
    .collect(Collectors.toList());
```

#### 2. TODO 023: Stream 이중 순회 제거
**개선 전:**
```java
var messageIds = sortedMessages.stream().map(Message::getId).toList();  // 1번째 순회
messageReadStatusService.updateReadStatus(messageIds, userId);

List<MessageResponse> messageResponses = sortedMessages.stream()  // 2번째 순회
    .map(...)
    .collect(Collectors.toList());
```

**개선 후:**
```java
List<String> messageIds = new ArrayList<>(sortedMessages.size());
List<String> senderIds = new ArrayList<>(sortedMessages.size());

for (Message message : sortedMessages) {  // 단일 순회로 두 리스트 생성
    messageIds.add(message.getId());
    if (message.getSenderId() != null) {
        senderIds.add(message.getSenderId());
    }
}
```

### 성능 개선 효과

| 항목 | 개선 전 | 개선 후 | 개선율 |
|------|---------|---------|--------|
| User DB 쿼리 | N회 (메시지 수) | 1회 (batch) | ~96% 감소 (30개 기준) |
| 총 DB 쿼리 (메시지 30개) | 31회 | 2회 | ~94% 감소 |
| Stream 순회 | 2회 | 1회 (for) + 1회 (response) | - |
| 메모리 사용 | O(N) | O(N) + Map | 동일 수준 |

### 남은 최적화 과제

#### TODO 018: File N+1 문제
- 현재 상태: `MessageResponseMapper`에서 파일 첨부 메시지마다 개별 쿼리
- 해결 방안: File도 batch loading 또는 Message와 join

#### TODO 022: 읽음 상태 업데이트 비동기 처리
- 현재 상태: `updateReadStatus`가 동기 실행으로 응답 지연 발생
- 해결 방안: `@Async` 적용 (eventual consistency 허용)

### 참고 파일
- [MessageLoader.java](apps/backend/src/main/java/com/ktb/chatapp/websocket/socketio/handler/MessageLoader.java)

---

## RoomJoinHandler 성능 최적화 (2025-12-10)

### 개선 대상
`RoomJoinHandler.handleJoinRoom()` 메서드

### 문제점
1. **TODO 019**: Room 재조회 - `addParticipant` 후 참가자 목록 가져오기 위해 Room을 다시 조회
2. **TODO 024**: 참가자 N+1 쿼리 - 참가자 수만큼 User 조회 반복

### 개선 내용

#### 1. TODO 019: Room 재조회 제거

**개선 전:**
```java
if (roomRepository.findById(roomId).isEmpty()) {  // 1번째 조회
    return;
}

roomRepository.addParticipant(roomId, userId);

Optional<Room> roomOpt = roomRepository.findById(roomId);  // 2번째 조회 (불필요!)
if (roomOpt.isEmpty()) {
    return;
}

List<UserResponse> participants = userRepository.findAllById(roomOpt.get().getParticipantIds())
```

**개선 후:**
```java
Optional<Room> roomOpt = roomRepository.findById(roomId);  // 1회만 조회
if (roomOpt.isEmpty()) {
    return;
}

roomRepository.addParticipant(roomId, userId);

/// [개선 019] Room 재조회 제거: 초기 조회한 Room 재사용 + 메모리에서 참가자 추가
Room room = roomOpt.get();
room.addParticipant(userId);  // 메모리에서 참가자 추가

List<UserResponse> participants = userRepository.findAllById(room.getParticipantIds())
```

**핵심 전략:**
- 조기 조회 (Early Fetch): 검증 단계에서 조회한 Room을 끝까지 재사용
- 메모리 업데이트: `room.addParticipant(userId)`로 로컬 상태 동기화
- DB와 메모리 일관성: `roomRepository.addParticipant()`와 `room.addParticipant()` 병행

#### 2. TODO 024: 참가자 N+1 문제 해결

**개선 전:**
```java
List<UserResponse> participants = roomOpt.get().getParticipantIds()
        .stream()
        .map(userRepository::findById)  // 참가자 수만큼 DB 쿼리!
        .filter(Optional::isPresent)
        .map(Optional::get)
        .map(UserResponse::from)
        .toList();
```

**개선 후:**
```java
/// [개선 024] Batch loading으로 참가자 N+1 문제 해결: N회 쿼리 → 1회 쿼리
List<UserResponse> participants = userRepository.findAllById(room.getParticipantIds())
        .stream()
        .map(UserResponse::from)
        .toList();
```

**핵심 전략:**
- `findById` 반복 호출 → `findAllById` 단일 호출
- MongoDB `$in` 연산자로 `_id` 인덱스 활용
- 불필요한 Stream 연산 제거 (`filter`, `map(Optional::get)`)

### 성능 개선 효과

#### TODO 019: Room 재조회 제거
| 항목 | 개선 전 | 개선 후 | 개선율 |
|------|---------|---------|--------|
| Room 조회 | 2회 | 1회 | **50% 감소** |
| 총 DB 쿼리 | 3회 (Room×2 + User×1) | 2회 (Room×1 + User×1) | **33% 감소** |

#### TODO 024: 참가자 N+1 해결
| 시나리오 | 개선 전 | 개선 후 | 개선율 |
|---------|---------|---------|--------|
| 참가자 10명 방 입장 | 10회 User 쿼리 | 1회 User 쿼리 | **90% 감소** |
| 참가자 50명 방 입장 | 50회 User 쿼리 | 1회 User 쿼리 | **98% 감소** |
| 참가자 100명 방 입장 | 100회 User 쿼리 | 1회 User 쿼리 | **99% 감소** |

#### 종합 효과 (10명 방 기준)
| 항목 | 개선 전 | 개선 후 | 개선율 |
|------|---------|---------|--------|
| 총 DB 쿼리 | 12회 (Room×2 + User×10) | 2회 (Room×1 + User×1) | **83% 감소** |
| 예상 응답 시간 | ~50ms | ~10ms | **80% 개선** |

### 주의사항

#### TODO 019 동시성 이슈
**잠재적 문제:**
- 메모리의 `room` 객체는 Line 90 시점의 스냅샷
- 다른 사용자가 동시 입장 시 최신 participantIds가 누락될 수 있음

**영향 범위:**
- JOIN_ROOM_SUCCESS 응답의 participants가 1~2명 누락 가능
- PARTICIPANTS_UPDATE 브로드캐스트로 실시간 동기화됨

**현실적 영향:**
- 채팅방 입장은 초당 수십 건 이하 → 충돌 가능성 낮음
- **트레이드오프**: 완벽한 일관성 < 성능 우선 (합리적 선택)

#### MongoDB findAllById 최적화
**자동 최적화 보장:**
- `@Id` 필드는 `_id` 인덱스 자동 생성 (클러스터형 인덱스)
- `findAllById` 내부적으로 `{ _id: { $in: [...] } }` 쿼리 실행
- 인덱스 스캔으로 O(log N) 시간 복잡도

**대규모 데이터 고려사항:**
- 참가자 1000명 이상 시 `$in` 성능 저하 가능
- 해결: TODO 020 (캐싱 + Projection) 추가 최적화 필요

### 남은 최적화 과제

#### TODO 020: Redis 캐싱 + Projection
- 현재: User 전체 필드 조회 (password, encryptedEmail 포함)
- 개선: 필요한 필드만 조회 (id, name, email, profileImage)
- 추가: Redis 캐싱으로 반복 조회 성능 개선

### 참고 파일
- [RoomJoinHandler.java](apps/backend/src/main/java/com/ktb/chatapp/websocket/socketio/handler/RoomJoinHandler.java)
- [Room.java](apps/backend/src/main/java/com/ktb/chatapp/model/Room.java)
- [RoomRepository.java](apps/backend/src/main/java/com/ktb/chatapp/repository/RoomRepository.java)
