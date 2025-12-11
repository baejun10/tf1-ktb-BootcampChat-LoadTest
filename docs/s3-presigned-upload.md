# S3 Presigned 업로드 구현 문서

## 1. 목표 요약
- E2E 테스트(`e2e/tests/chat.spec.js`)는 수정 불가, `/api/files/upload` 응답 형태와 DOM 구조를 그대로 유지해야 함.
- 실제 파일 바이트는 프론트 ➜ S3로 직접 전송하고, 백엔드는 검증·메타데이터 저장·권한 확인만 담당.
- 기존 multipart 업로드도 호환되도록 하여 회귀 위험을 최소화.

## 2. 전체 흐름
1. 프론트(`apps/frontend/services/fileService.js:77-175`)가 파일 선택 후 `/api/files/presign`에 파일명/MIME/크기 정보를 POST.
2. 백엔드 `FileController#createPresignedUpload`(`apps/backend/src/main/java/com/ktb/chatapp/controller/FileController.java:59-71`)가 인증 사용자 확인 → `PresignedUploadService` 호출.
3. `PresignedUploadService`(`apps/backend/src/main/java/com/ktb/chatapp/service/PresignedUploadService.java`)가
   - `FileUtil.validateFileMetadata`로 사전 검증 (`apps/backend/src/main/java/com/ktb/chatapp/util/FileUtil.java:46-82`)
   - Mongo에 `PresignedUpload` 문서를 저장 (`apps/backend/src/main/java/com/ktb/chatapp/model/PresignedUpload*.java`)
   - S3 Presigner로 PUT URL/헤더/만료시간을 생성해 반환.
4. 프론트는 받은 `uploadUrl`로 `axios.put`을 수행 (서버 거치지 않음) 후 `/api/files/upload`에 `uploadId`만 담아 호출.
5. 백엔드 `FileController#uploadFile`는 `file` 파라미터가 없으면 `PresignedUploadService.finalizeUpload`를 호출해
   - S3 `HeadObject`로 업로드 완료 여부와 사이즈 확인
   - `files` 컬렉션에 메타데이터를 저장 (`FileRepository`)
   - 기존과 동일한 JSON 구조를 응답.
6. 채팅 메시지 소켓 이벤트는 응답으로 받은 `filename`/`originalname`을 그대로 사용하므로 DOM과 E2E 검증이 유지된다.

## 3. 주요 컴포넌트
| 영역 | 파일 | 내용 |
| --- | --- | --- |
| S3 설정 | `apps/backend/src/main/java/com/ktb/chatapp/config/S3Config.java` | S3Client/S3Presigner 빈, endpoint·path-style 옵션 지원 |
| 파일 서비스 | `apps/backend/src/main/java/com/ktb/chatapp/service/S3FileService.java` | S3 Put/Get/Delete + 권한 검증, 프로필 이미지 삭제 지원 |
| Presigned 관리 | `apps/backend/src/main/java/com/ktb/chatapp/service/PresignedUploadService.java` | 업로드 세션 생성/만료/완료 처리 |
| 컨트롤러 | `apps/backend/src/main/java/com/ktb/chatapp/controller/FileController.java` | `/presign`, `/upload`, `/download`, `/view` |
| 프런트 서비스 | `apps/frontend/services/fileService.js` | presign → S3 PUT → finalize 흐름 구현 |
| 환경 변수 | `apps/backend/src/main/resources/application.properties:17-24` | 버킷/리전/만료/공개 URL 등 |

## 4. E2E/계약 보장
- `/api/files/upload` 응답 JSON( `success`, `file.{_id,filename,originalname,mimetype,size}` )이 기존과 동일하므로 `chat.spec.js`의 DOM 검증(`profile.jpg`, `file-image-preview`)이 유지된다.
- `/api/files/view/{filename}`·`/api/files/download/{filename}`도 같은 경로를 사용해 프론트 구현과 E2E가 변경되지 않는다.
- 필요 시 multipart 업로드(`file` 파라미터 포함)를 그대로 지원해 회귀 시 빠르게 롤백 가능.

## 5. 테스트 가이드
1. **백엔드 단위/통합**: `cd apps/backend && ./mvnw test`
2. **E2E**: `cd e2e && npx playwright test tests/chat.spec.js`
3. **수동 검증**:
   - 브라우저 Network 탭에서 `/api/files/presign` → S3 PUT → `/api/files/upload` 호출 순서를 확인.
   - S3 버킷에서 객체가 생성되고, Mongo `files` 문서에 `path`가 S3 키로 저장되는지 확인.
4. **오류 시나리오**:
   - Presign 만료 후 finalize 호출 → 400/404 응답인지 확인.
   - S3 업로드 실패 상황에서 finalize 호출 시 에러 메시지가 적절히 반환되는지 확인.

## 6. 한계 및 TODO
- Presigned 세션 파기/청소 전략(TTL 인덱스, 주기적 정리)이 필요.
- 다중 파트 업로드, 재시도, 대용량 업로드 최적화 등은 추후 요구사항에 따라 확장.
- CDN/CloudFront 연동 및 공개 URL 캐싱 전략은 아직 미구현.
