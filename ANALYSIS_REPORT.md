# Artillery 파일 업로드 에러 분석 및 해결 방안

## 📋 목차
1. [초기 문제](#초기-문제)
2. [근본 원인 분석](#근본-원인-분석)
3. [2가지 에러의 차이](#2가지-에러의-차이)
4. [최종 해결 방안: Skeleton Loading](#최종-해결-방안-skeleton-loading)
5. [체크리스트](#체크리스트)

---

## 초기 문제

### 증상
Artillery 부하 테스트 중 파일 업로드 시나리오에서 다음과 같은 에러 발생:

```
⠸ File upload scenario failed: expect(locator).toBeVisible() failed

Locator: getByTestId('file-message-container').filter({ hasText: '파일 업로드 부하 테스트 1765458804678' })
Expected: visible
Timeout: 10000ms
Error: element(s) not found
```

### 테스트 환경
- Artillery 부하 테스트
- Phase 구성: 5단계 (1명 → 5명 → 10명 → 20명 → 40명)
- 각 VU는 7개 시나리오 순차 실행
- 파일: profile.jpg (이미지)

---

## 근본 원인 분석

### 1️⃣ 프론트엔드 메시지 수신 문제

**위치:** [useChatRoom.js:249-266]

```javascript
socketRef.current.on('message', message => {
  if (!message || !mountedRef.current || messageProcessingRef.current || !message._id) return;
  // ... 메시지 추가
});
```

**문제:**
- `messageProcessingRef.current` 플래그가 true일 때 새 메시지가 무시됨
- 이전 메시지 처리 중 파일 메시지 도착 → 메시지 유실

**해결:** `messageProcessingRef.current` 체크 제거

---

### 2️⃣ 프론트엔드 렌더링 타이밍

**위치:** [FileMessage.js:42-45]

```javascript
if (!msg?.file) {
  console.error('File data is missing:', msg);
  return null;  // ❌ 렌더링 중지
}
```

**문제:**
- 파일 메타데이터 없으면 컴포넌트 전체가 렌더링 안 됨
- `file-message-container`가 DOM에 나타나지 않음
- 테스트에서 요소를 찾을 수 없음

---

### 3️⃣ 이미지 로드 대기

**흐름:**
```
1. 파일 업로드 API 200 응답
   ↓
2. 소켓 'message' 이벤트 수신
   ↓
3. FileMessage 컴포넌트 렌더링
   ↓
4. useEffect에서 이미지 URL 생성
   ↓
5. <img src={...} /> 렌더링 (네트워크 요청)
   ↓
6. onLoad 완료 (시간 소요) ← 테스트가 여기까지 기다려야 함
```

**문제:** 파일 메타데이터가 있어도 이미지 로드까지 시간이 걸림

---

## 2가지 에러의 차이

### 에러 1: DOM 렌더링 실패
```
expect(locator).toBeVisible() failed
Error: element(s) not found
```

- **실패 지점:** `expect(fileMessageContainer).toBeVisible()` [95줄]
- **API 상태:** ✅ HTTP 200 응답 완료
- **문제:** file-message-container가 DOM에 없음
- **원인:** `msg.file`이 없어서 컴포넌트가 null 반환

### 에러 2: API 타임아웃
```
page.waitForResponse: Timeout 15000ms exceeded
```

- **실패 지점:** `await uploadPromise` [90줄]
- **API 상태:** ❌ 15초 내 응답 없음
- **문제:** 백엔드에서 파일 저장 완료 못 함
- **원인:** 부하 테스트 중 동시 파일 업로드 (5명+ 동시)

---

## 최종 해결 방안: Skeleton Loading

### 핵심 아이디어

```
1️⃣ 파일 메타데이터 도착 (file 필드 없을 수도 있음)
   ↓
2️⃣ ✅ file-message-container 즉시 렌더링 (로딩 스피너)
   ↓
3️⃣ ✅ 테스트: file-message-container 발견 → 통과!
   ↓
4️⃣ (백그라운드에서) 파일 메타데이터 업데이트 + 이미지 URL 생성 & 로드
   ↓
5️⃣ 이미지 로드 완료 → 로딩 스피너 제거, 이미지 표시
```

### 왜 이 방식이 필요한가?

| 측면 | 효과 |
|------|------|
| **테스트 안정성** | ✅ file-message-container 즉시 발견 |
| **UX** | ✅ 로딩 스피너로 진행 상황 표시 |
| **부하 대응** | ✅ 메타데이터만으로 컨테이너 생성 |
| **에러 분리** | ✅ 렌더링 에러와 이미지 로드 에러 구분 |

---

## 구현 방법

### 방법 1: 메타데이터 분리 (Best Practice)

**백엔드에서 두 번 emit:**

```
1️⃣ 파일 저장 완료 → socket.emit('message', {
     _id: uuid,
     type: 'file',
     content: '...',
     sender: {...},
     // file은 없음!
   })

2️⃣ (거의 동시에) → socket.emit('updateMessage', {
     _id: uuid,
     file: {
       _id: '...',
       filename: '...',
       mimetype: '...',
       size: 12345
     }
   })
```

### 방법 2: 프론트엔드 조건부 렌더링 (더 간단)

**FileMessage.js에서:**

```javascript
const hasFileMetadata = msg?.file?.filename && msg?.file?.mimetype;

return (
  <div data-testid="file-message-container">
    {!hasFileMetadata ? (
      // ✅ 로딩 상태 표시
      <LoadingSpinner />
    ) : (
      // ✅ 실제 파일 렌더링
      renderFilePreview()
    )}
  </div>
);
```

---

## FileMessage.js 수정 가이드

### Step 1: messageProcessingRef 체크 제거
**위치:** [useChatRoom.js:249-250]

변경 전:
```javascript
if (!message || !mountedRef.current || messageProcessingRef.current || !message._id) return;
```

변경 후:
```javascript
if (!message || !mountedRef.current || !message._id) return;
```

### Step 2: null 체크 제거
**위치:** [FileMessage.js:42-45]

삭제할 코드:
```javascript
if (!msg?.file) {
  console.error('File data is missing:', msg);
  return null;
}
```

### Step 3: 상태 추가
**위치:** [FileMessage.js:28-30]

```javascript
const [error, setError] = useState(null);
const [previewUrl, setPreviewUrl] = useState('');
const [imageLoaded, setImageLoaded] = useState(false);  // ✅ 추가
const messageDomRef = useRef(null);
```

### Step 4: renderFilePreview에 조건 추가

```javascript
const renderFilePreview = () => {
  // ✅ 메타데이터 없으면 로딩 스피너만 표시
  if (!msg?.file) {
    return (
      <div className="flex items-center justify-center h-40 bg-gray-900 rounded-md">
        <div className="spinner-border spinner-border-sm text-gray-400" role="status">
          <span className="visually-hidden">Loading...</span>
        </div>
      </div>
    );
  }

  const mimetype = msg.file?.mimetype || '';
  // ... 나머지 코드
};
```

### Step 5: renderImagePreview에서 로딩 상태 표시

```javascript
return (
  <div className="bg-transparent-pattern relative">
    {/* ✅ 로딩 중일 때 스피너 표시 */}
    {!imageLoaded && (
      <div className="absolute inset-0 flex items-center justify-center bg-gray-900 bg-opacity-50 rounded-md">
        <div className="spinner-border spinner-border-sm text-gray-400" role="status">
          <span className="visually-hidden">Loading...</span>
        </div>
      </div>
    )}

    <img
      src={previewUrl}
      alt={originalname}
      onLoad={() => {
        console.debug('Image loaded successfully:', originalname);
        setImageLoaded(true);  // ✅ 로딩 완료
      }}
      onError={(e) => {
        console.error('Image load error:', e);
        setImageLoaded(true);  // ✅ 에러 후에도 처리
        setError('이미지를 불러올 수 없습니다.');
      }}
      data-testid="file-image-preview"
    />
  </div>
);
```

### Step 6: formattedTime 안전하게

변경 전:
```javascript
const formattedTime = new Date(msg.timestamp).toLocaleString('ko-KR', {
```

변경 후:
```javascript
const formattedTime = msg?.timestamp ? new Date(msg.timestamp).toLocaleString('ko-KR', {
  year: 'numeric',
  month: 'long',
  day: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false
}).replace(/\./g, '년').replace(/\s/g, ' ').replace('일 ', '일 ') : '시간 불명';
```

---

## Artillery 시나리오 추가 사항

### 웹소켓 연결 확인 추가

[chat.actions.js]에 함수 추가:

```javascript
/**
 * 웹소켓 연결 대기
 */
async function waitForSocketConnection(page, timeout = 20000) {
  await page.waitForFunction(
    () => {
      try {
        return window.socketService &&
               window.socketService.isConnected() === true;
      } catch (e) {
        return false;
      }
    },
    { timeout, polling: 500 }
  );
}

/**
 * 웹소켓 연결 상태 확인
 */
async function getSocketConnectionStatus(page) {
  return page.evaluate(() => {
    if (!window.socketService) {
      return { status: 'not_loaded', message: 'Socket service not loaded' };
    }

    return {
      status: window.socketService.isConnected() ? 'connected' : 'disconnected',
      connected: window.socketService.isConnected(),
      quality: window.socketService.getConnectionQuality(),
      socketId: window.socketService.socket?.id || 'unknown',
      transport: window.socketService.socket?.conn?.transport?.name || 'unknown'
    };
  });
}
```

### fileUploadScenario 개선

```javascript
async function fileUploadScenario(page, vuContext) {
    try {
        // 1. 랜덤 채팅방 입장
        await joinRandomChatRoomAction(page);
        await expect(page).toHaveURL(new RegExp(`${BASE_URL}/chat/\\w+`));

        // ✅ 웹소켓 연결 확인
        console.log('Waiting for socket connection...');
        await waitForSocketConnection(page, 20000);

        const socketStatus = await getSocketConnectionStatus(page);
        console.log('Socket status:', socketStatus);

        if (!socketStatus.connected) {
            throw new Error(`Socket not connected: ${JSON.stringify(socketStatus)}`);
        }

        // 2. 이미지 파일 업로드
        const filePath = path.resolve(__dirname, '../../fixtures/images/profile.jpg');
        const message = `파일 업로드 부하 테스트 ${Date.now()}`;

        const uploadPromise = page.waitForResponse(
            response => response.url().includes('/api/files/upload') && response.status() === 200,
            { timeout: 45000 }  // ✅ 타임아웃 증가
        );

        await uploadFileAction(page, filePath, message);
        await uploadPromise;

        // ✅ file-message-container 즉시 발견
        const fileMessageContainer = page.getByTestId('file-message-container').last();
        await fileMessageContainer.waitFor({ state: 'visible', timeout: 20000 });

    } catch (error) {
        console.error('File upload scenario failed:', error.message);
        const socketStatus = await getSocketConnectionStatus(page);
        console.error('Socket status at error:', socketStatus);
        throw error;
    }
}
```

---

## 체크리스트

### 현재 원본 파일 상태 확인

- [ ] [42-45줄] null 체크가 삭제되었는가?
  ```javascript
  if (!msg?.file) { return null; }  // ← 이 4줄이 없어야 함
  ```

- [ ] [47줄] formattedTime이 안전한가?
  ```javascript
  const formattedTime = msg?.timestamp ? new Date(...) : '시간 불명'
  ```

- [ ] [renderFilePreview 시작] 파일 메타데이터 체크
  ```javascript
  const renderFilePreview = () => {
    if (!msg?.file) {
      return <LoadingSpinner />;  // ← 이 부분이 있어야 함
    }
  };
  ```

- [ ] [renderImagePreview] img 태그의 onLoad/onError
  ```javascript
  onLoad={() => { setImageLoaded(true); }}
  onError={() => { setImageLoaded(true); }}
  ```

- [ ] [useChatRoom.js:249-250] messageProcessingRef 체크 제거
  ```javascript
  if (!message || !mountedRef.current || !message._id) return;  // messageProcessingRef 제거
  ```

---

## 결과 기대치

### 수정 전
```
파일 업로드 → 메타데이터 대기 → 이미지 URL 생성 → 이미지 로드 → 테스트 통과
                (에러 가능)      (시간 소요)         (더 시간 소요)
```

### 수정 후
```
파일 메타데이터 → ✅ file-message-container 생성 → ✅ 테스트 통과!
  (즉시)           (로딩 스피너)
      ↓
  (백그라운드) 이미지 URL 생성 → 이미지 로드 → 완료
```

---

## 참고 자료

### 소켓 서비스
- 파일: [apps/frontend/services/socket.js](apps/frontend/services/socket.js)
- 주요 메서드:
  - `isConnected()` - 웹소켓 연결 상태
  - `getConnectionQuality()` - 연결 품질

### 관련 컴포넌트
- [FileMessage.js](apps/frontend/components/FileMessage.js) - 파일 메시지 렌더링
- [useChatRoom.js](apps/frontend/hooks/useChatRoom.js) - 채팅방 로직
- [chat.scenario.js](e2e/artillery/scenarios/chat.scenario.js) - Artillery 시나리오

### 관련 액션
- [chat.actions.js](e2e/actions/chat.actions.js) - 채팅 관련 액션
- [auth.actions.js](e2e/actions/auth.actions.js) - 인증 액션
