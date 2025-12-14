# Profile Image Presigned URL 403 Error 해결

## 문제 상황

Profile Image 업로드 로직에서 S3 Presigned URL을 사용하여 이미지를 업로드할 때 403 Forbidden 오류가 발생했습니다.

### 오류 내용

```
Request URL: https://bootcamp-chat-files.s3.ap-northeast-2.amazonaws.com/uploads/profiles/...
Request Method: PUT
Status Code: 403 Forbidden

Error: SignatureDoesNotMatch - The request signature we calculated does not match the signature you provided.
```

### 흐름 분석

1. **Presign 요청 성공** (200 OK)
   - `POST /api/users/profile-image/presign`
   - uploadUrl, headers, uploadId 반환

2. **S3 업로드 실패** (403 Forbidden)
   - `PUT https://bootcamp-chat-files.s3.ap-northeast-2.amazonaws.com/...`
   - 서명 불일치 오류 발생

3. **Finalize 요청** (200 OK)
   - `POST /api/users/profile-image/finalize`
   - S3에 파일이 없어 최종적으로 이미지 업로드 실패

## 근본 원인 분석

### 1. File 업로드와 Profile Image 업로드 비교

**File 업로드** (정상 동작):
```javascript
// apps/frontend/services/fileService.js
await axios.put(uploadUrl, file, {
  headers: {
    ...headers,
    'Content-Type': file.type  // ✅ Content-Type 명시적 설정
  },
  withCredentials: false,
  ...
});
```

**Profile Image 업로드** (403 오류):
```javascript
// apps/frontend/components/ProfileImageUpload.js (Before)
await fetch(uploadUrl, {
  method: 'PUT',
  headers: headers,  // ❌ 서버에서 받은 headers만 사용
  body: file
});
```

### 2. S3 Presigned URL 서명 메커니즘

S3 Presigned URL은 다음 정보를 기반으로 HMAC-SHA256 서명을 생성합니다:

```java
// apps/backend/src/main/java/com/ktb/chatapp/service/PresignedUploadService.java
PutObjectRequest objectRequest = PutObjectRequest.builder()
    .bucket(bucketName)
    .key(key)
    .contentType(mimetype)  // ← Content-Type이 서명에 포함됨
    .build();
```

서명에 포함된 헤더:
- `X-Amz-SignedHeaders=content-type%3Bhost`
- 서명 생성 시 사용된 `Content-Type` 값이 고정됨

### 3. 문제의 핵심

**브라우저 fetch API의 동작:**
1. `body`가 File 객체일 때, 브라우저가 자동으로 `Content-Type` 헤더를 추론하여 설정
2. 개발자가 명시적으로 설정한 `Content-Type`이 있어도, 브라우저가 덮어쓸 수 있음
3. 브라우저가 추론한 값 ≠ 서명에 포함된 값 → **403 SignatureDoesNotMatch**

**예시:**
```
서명 생성 시: Content-Type: image/png
실제 요청 시: Content-Type: image/png (브라우저가 자동 설정한 값)
→ 미묘한 차이로 인해 서명 불일치 발생 가능
```

또한, `credentials` 설정이 누락되어 불필요한 인증 헤더가 전송될 수 있습니다:
- S3 Presigned URL은 인증 정보가 URL에 포함되어 있음
- 추가 인증 헤더(Cookie, Authorization 등)는 서명을 무효화할 수 있음

## 해결 방법

### 수정된 코드

```javascript
// apps/frontend/components/ProfileImageUpload.js (After)
const { uploadUrl, headers, uploadId } = await presignResponse.json();

// Headers 객체를 사용하여 헤더를 명시적으로 관리
const uploadHeaders = new Headers();
if (headers) {
  Object.entries(headers).forEach(([key, value]) => {
    uploadHeaders.set(key, value);
  });
}
// Content-Type을 명시적으로 재설정
uploadHeaders.set('Content-Type', file.type);

await fetch(uploadUrl, {
  method: 'PUT',
  headers: uploadHeaders,
  body: file,
  credentials: 'omit'  // 불필요한 인증 헤더 제거
});
```

### 핵심 개선 사항

1. **Headers 객체 사용**
   - 서버에서 받은 헤더를 명시적으로 설정
   - `Content-Type`을 확실하게 재설정하여 브라우저의 자동 추론 방지

2. **credentials: 'omit' 설정**
   - S3로 직접 업로드 시 불필요한 쿠키/인증 헤더 전송 방지
   - Presigned URL에 이미 인증 정보가 포함되어 있으므로 추가 인증 불필요

## 검증

### 수정 전 요청 헤더
```
PUT https://bootcamp-chat-files.s3.ap-northeast-2.amazonaws.com/...
Content-Type: (브라우저가 자동 설정)
Cookie: ... (불필요한 헤더)
```

### 수정 후 요청 헤더
```
PUT https://bootcamp-chat-files.s3.ap-northeast-2.amazonaws.com/...
Content-Type: image/png (명시적 설정)
(인증 관련 헤더 없음)
```

## 교훈

### S3 Presigned URL 사용 시 주의사항

1. **Content-Type 명시적 설정 필수**
   - 브라우저의 자동 추론에 의존하지 말 것
   - 서명 생성 시 사용한 값과 정확히 일치해야 함

2. **credentials 옵션 제어**
   - Presigned URL 사용 시 `credentials: 'omit'` 권장
   - 불필요한 헤더가 서명을 무효화할 수 있음

3. **File 업로드 vs Profile Image 업로드**
   - axios는 `withCredentials: false`가 기본적으로 적용됨
   - fetch는 명시적으로 `credentials: 'omit'` 설정 필요

### 디버깅 팁

S3 Presigned URL 403 오류 발생 시 확인 사항:
1. URL의 `X-Amz-SignedHeaders` 파라미터 확인
2. 실제 요청 헤더와 서명된 헤더 비교
3. Content-Type 값이 정확히 일치하는지 확인
4. 불필요한 헤더(Cookie, Authorization 등) 제거

## 참고 자료

- [AWS S3 Presigned URL Documentation](https://docs.aws.amazon.com/AmazonS3/latest/userguide/PresignedUrlUploadObject.html)
- [MDN fetch API - credentials](https://developer.mozilla.org/en-US/docs/Web/API/fetch#credentials)
- 관련 코드:
  - Backend: `apps/backend/src/main/java/com/ktb/chatapp/service/PresignedUploadService.java`
  - Frontend: `apps/frontend/components/ProfileImageUpload.js`
  - 비교 대상: `apps/frontend/services/fileService.js`
