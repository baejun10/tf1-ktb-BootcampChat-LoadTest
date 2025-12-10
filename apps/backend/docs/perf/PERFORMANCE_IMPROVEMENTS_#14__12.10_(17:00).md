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
