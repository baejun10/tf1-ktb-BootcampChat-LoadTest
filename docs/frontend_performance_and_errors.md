# Frontend 성능 및 에러 캐치 개선 항목 (Load Test 관점)

> 비즈니스 요구사항은 제외하고, 부하 테스트 및 장애 상황에서의 **성능**과 **에러 감지/복원력**에만 집중한 개선 리스트입니다.

---

## 1. 렌더링 / DOM 처리

- `apps/frontend/pages/chat/[room].js`
  - **문제**: `height="calc(100vh - 80px"` 에서 닫는 괄호 누락 → 브라우저마다 레이아웃 깨짐 및 리플로우 증가 가능.
  - **개선**: `height="calc(100vh - 80px)"` 로 수정하거나, 스타일을 CSS/스타일 객체로 분리해 정적 검증 가능하게 처리.

- `apps/frontend/components/ChatMessages.js`
  - **문제**: `messages.sort(...)` 를 직접 호출하여, `props`로 받은 배열을 매 렌더마다 mutable 정렬 → 불필요한 연산 + 예측 불가능한 부수효과.
  - **개선**: `const allMessages = useMemo(() => messages.slice().sort(...), [messages]);` 와 같이 얕은 복사 후 정렬.

- `apps/frontend/components/ChatMessages.js`
  - **문제**: 메시지 수가 많아질수록 전체 리스트를 매번 렌더링 → 스크롤 부하 테스트 시 CPU 사용량 급증 위험.
  - **개선**: `react-window` 등 가상 스크롤 도입, 혹은 최근 N개 메시지만 렌더하고 나머지는 별도 뷰로 분리.

- `apps/frontend/hooks/useAutoScroll.js`
  - **문제**: 스크롤 복원/자동 스크롤 로직이 `setTimeout`(300ms) 등에 의존해 타이밍에 민감함 → 높은 부하 또는 느린 기기에서 불안정한 스크롤 동작 가능.
  - **개선**: `requestAnimationFrame` 또는 실제 DOM 업데이트 완료 후 이벤트 기반으로 스크롤, 혹은 로직을 단순화하여 타이밍 의존성을 줄임.

---

## 2. 메시지 로딩 / 페이징 성능

- `apps/frontend/hooks/useMessageHandling.js`
  - **문제**: `handleLoadMore`에서 매번 `messages` 전체를 복사 및 정렬해 가장 오래된 메시지를 찾음 → 과거 메시지가 많을수록 비용이 O(n log n)으로 증가.
  - **개선**: 
    - 항상 시간 순으로 정렬된 상태를 유지하고, 가장 앞 요소(`messages[0]`)의 timestamp만 사용.
    - 또는 서버에서 `before` 커서를 내려받아 그대로 재사용(프론트에서는 정렬 최소화).

- `apps/frontend/hooks/useChatRoom.js`
  - **문제**: `processMessages`에서 새 메시지와 기존 메시지를 합친 뒤, 매번 전체 정렬 및 `Map`으로 중복 제거 → 메시지 수 증가 시 지속적인 O(n log n) 비용.
  - **개선**:
    - 서버에서 timestamp 오름차순을 보장받는다면, 단순 병합(두 개의 정렬된 배열 merge)으로 비용을 줄임.
    - `_id` 중복 여부 체크용 `Set`을 유지해 전체 배열을 다시 순회하지 않고 중복을 사전에 필터링.

- `apps/frontend/hooks/useInfiniteScroll.js`
  - **문제**: `IntersectionObserver`를 이용해 상단 sentinel 관찰 시, 옵션 객체를 deps에 직접 넣어 재생성되면 Observer가 불필요하게 자주 재설치될 수 있음.
  - **개선**: 옵션을 훅 내부에서 상수로 두거나, `useMemo`로 안정적인 객체를 사용해 Observer 재생성을 최소화.

---

## 3. 소켓 연결 / 재연결 로직

- `apps/frontend/services/socket.js`
  - **문제**: `messageQueue`에 실패한 이벤트를 최대 5분까지 유지하면서 큐 크기 제한이 없음 → 장애 구간에서 큐가 비정상적으로 커질 가능성.
  - **개선**:
    - 큐 최대 길이(예: 100~200개) 설정 후 초과 시 가장 오래된 항목부터 드롭.
    - 큐가 일정 크기 이상이면 경고 로그 출력 혹은 사용자에게 “일시적으로 메시지가 지연됨” 표시.

- `apps/frontend/services/socket.js`
  - **문제**: `startHeartbeat`에서 ping 실패 시 바로 `cleanup` 호출로 전체 연결을 끊음 → 일시적인 네트워크 문제에도 전체 재연결이 반복될 수 있음.
  - **개선**:
    - 연속 실패 횟수를 카운트하고, 일정 횟수 이상일 때만 강제 종료.
    - ping RTT(왕복 시간)를 측정해 연결 품질을 수치화하고, 일정 이상 느려지면 단계적으로 재연결 시도.

- `apps/frontend/hooks/useSocketHandling.js`
  - **문제**: `socketRef.current`를 `useEffect` deps에 직접 넣는 패턴 사용 → 리액트 의존성 추적과 맞지 않으며, 실제로는 변경 감지가 안정적이지 않음.
  - **개선**:
    - deps에서는 `socketRef`만 참조하고, 내부에서 `socketRef.current`를 읽도록 변경.
    - 또는 `const [socket, setSocket] = useState(null);` 형태로 socket 인스턴스를 state로 관리해 더 명확하게 의존성 관리.

- `apps/frontend/hooks/useSocketHandling.js`
  - **문제**: `socketRef.current.connect()`와 `socketService.connect()`를 혼용해 사용 → 연결 관리 책임이 분산되어 장애 분석이 어려움.
  - **개선**: 모든 소켓 생성/재연결은 `socketService.connect()` 한 곳에서만 담당하고, 훅에서는 반환된 socket에 대한 이벤트 바인딩/상태 업데이트만 수행.

- `apps/frontend/hooks/useRoomHandling.js`
  - **문제**: `setupSocket`에서 기존 소켓이 있을 때 `removeAllListeners` 후 즉시 `disconnect` 및 재접속 → 이벤트 해제/재등록이 많은 부하 상황에서 비용 발생 및 잠재적 버그 위험.
  - **개선**:
    - 동일 소켓 인스턴스를 재사용하면서, 룸 변경 시 `leaveRoom` / `joinRoom`만 수행.
    - 정말 필요할 때만 소켓을 새로 생성하고, 재생성 회수를 제한.

---

## 4. 초기화 / 클린업 및 상태 일관성

- `apps/frontend/hooks/useChatRoom.js`
  - **문제**: `cleanup`과 `setupEventListeners`가 여러 레퍼런스/상태에 의존하며 복잡 → 재마운트/재연결 시 이벤트 리스너 중복 등록, 메시지 중복 수신 가능성.
  - **개선**:
    - 소켓 이벤트 등록/해제를 담당하는 전용 유틸 혹은 훅으로 분리하고, “한 번만 등록되고 확실히 해제되는지”를 보장.
    - 소켓 인스턴스 기준으로 `once` 사용 또는 `roomId`를 포함한 네임스페이스 기반 이벤트 처리.

- `apps/frontend/hooks/useRoomHandling.js`
  - **문제**: `setupRoom`에서 소켓 연결, 룸 데이터 fetch, join, 메시지 로딩까지 모두 한 번에 처리 → 한 단계 실패 시 전체 초기화 실패로 이어지며 회복 로직이 복잡해짐.
  - **개선**:
    - (1) 소켓 연결 → (2) 룸 메타데이터 fetch → (3) join → (4) 이전 메시지 로딩 을 각각 독립 함수로 두고, 단계별 타임아웃/재시도 정책을 단순화.
    - 실패 단계에 따라 최소한의 재시도만 수행하고, 실패 원인을 로그/상태로 명시.

- `apps/frontend/hooks/useRoomHandling.js`
  - **문제**: `window.online` 이벤트 발생 시마다 `setupRoom`을 재호출 → 네트워크가 자주 끊기는 환경에서 중복 호출 가능.
  - **개선**:
    - `setupPromiseRef`와 별도 플래그를 사용해, 이미 초기화가 진행 중이면 추가 호출을 무시.
    - 마지막 실패 원인/시간을 기록하여, 너무 잦은 재시도를 제한.

- `apps/frontend/hooks/useChatRoom.js`
  - **문제**: `useChatRoom`이 소켓/메시지/리액션/파일/세션까지 모두 관리 → 상태 및 ref가 많아 예외 상황(에러/중단)에서 일관성 유지가 어려움.
  - **개선**:
    - 역할별로 훅을 더 분할(예: `useChatMessagesState`, `useChatConnectionState`)하고, 상태 전이를 `useReducer` 기반으로 명시적으로 관리.
    - 에러 발생 시 어떤 단계에서 실패했는지 식별 가능한 에러 타입/코드를 도입.

---

## 5. 에러 감지 / 노출

- `apps/frontend/services/socket.js`
  - **문제**: `emit` 실패(타임아웃/연결 불가) 시 큐에만 적재하고 사용자에게는 구체적인 실패 원인을 노출하지 않음 → 부하 테스트 중 “메시지 유실/지연” 원인 파악이 어려움.
  - **개선**:
    - 큐에 쌓이는 이벤트 수/지속 시간에 따라 로그를 남기거나, UI에 “메시지가 지연되고 있음” 등의 상태를 표시.
    - `emit` 호출부에서 에러 종류(네트워크/서버/권한)를 구분해 별도 처리.

- `apps/frontend/hooks/useMessageHandling.js`
  - **문제**: 파일 메시지 전송에서 서버 응답의 성공/실패 외, 소켓 ack 콜백을 사용하지 않고 단순 `emit`만 수행 → 서버 측에서 실제 저장 실패 시 클라이언트에서 감지 어려움.
  - **개선**:
    - `socketRef.current.emit('chatMessage', payload, ackCallback)` 형태로 ack를 사용하고, ack 에러 시 업로드 에러와 동일하게 처리.

- `apps/frontend/hooks/useRoomHandling.js`
  - **문제**: `loadInitialMessages`에서 `previousMessagesLoaded` 이벤트/에러를 재시도하지만, 최종 실패 시 에러 원인(타임아웃/서버에러/형식 오류)을 구분해 노출하지 않음.
  - **개선**:
    - 타임아웃, 서버 에러, 형식 오류 각각에 대해 다른 에러 메시지/에러 코드로 `setError` 또는 로깅.
    - 부하 테스트 로그 분석을 위해 에러 타입을 구분 가능하게 남김.

- `apps/frontend/contexts/AuthContext.js`
  - **문제**: 토큰 검증/갱신 실패 시 내부에서 바로 `logout` 및 라우팅까지 처리 → 채팅 쪽 소켓/룸 로직과 섞여 실제 원인(인증 vs 네트워크)을 구분하기 어려움.
  - **개선**:
    - 인증 관련 에러를 하나의 일관된 에러 타입으로 throw하고, 채팅 훅/페이지에서 이를 받아 “인증 문제”로 태깅하여 로그/모니터링.
    - 소켓/룸 로직에서는 “인증 에러 발생”만 알고, 구체적인 UI는 상위 레벨에서 결정.

---

## 6. 모니터링 / 관찰 가능성(Observability)

- **문제**: 주요 오류가 `Toast`로만 노출되고, 구조화된 로그나 상태 코드 없이 문자열만 남음 → 부하 테스트 상황에서 문제의 위치/원인 파악이 어렵다.
- **개선**:
  - 공통 로거 유틸(예: `logError({ source, code, message, context })`)를 만들어, 소켓/메시지/룸/인증에서 공통 형식으로 에러와 컨텍스트를 기록.
  - 최소한 콘솔 로그라도 `source`(예: `chat:socket`, `chat:room:loadMessages`)와 함께 남겨, 부하 테스트 중 로그로 원인 추적이 가능하게 함.

