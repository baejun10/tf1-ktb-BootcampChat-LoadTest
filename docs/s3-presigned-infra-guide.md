# S3 Presigned 업로드 – 인프라 담당자 가이드

## 1. S3 버킷 & IAM
1. **버킷 생성**: `storage.s3.bucket`에 지정한 이름으로 버킷을 만들고, 버전 관리/암호화 옵션을 정책에 맞게 설정합니다.
2. **IAM 권한**: 백엔드 서버가 사용할 IAM Role 또는 Access Key에 아래 권한을 최소화해서 부여합니다.
   - `s3:PutObject`, `s3:GetObject`, `s3:DeleteObject`, `s3:HeadObject`
   - Presigned URL 발급 시에는 `s3:PutObject` 권한만으로 충분하지만, 파일 삭제/다운로드를 위해 위 권한이 필요합니다.
3. **폴더 구조**: `storage.s3.base-dir`(기본 `uploads`) 하위에 `chat/`, `profiles/` 등 논리 경로를 사용할 수 있도록 네이밍 정책을 정합니다.

## 2. CORS & 퍼블릭 액세스
1. **CORS 설정**: 브라우저가 Presigned URL로 PUT을 수행해야 하므로 S3 버킷 CORS 규칙에 프런트엔드 도메인을 명시합니다.
   ```xml
   <CORSRule>
     <AllowedOrigin>https://chat.example.com</AllowedOrigin>
     <AllowedMethod>PUT</AllowedMethod>
     <AllowedHeader>*</AllowedHeader>
     <ExposeHeader>ETag</ExposeHeader>
     <MaxAgeSeconds>3000</MaxAgeSeconds>
   </CORSRule>
   ```
2. **퍼블릭 URL**: 프리뷰/다운로드를 CDN으로 노출할 경우 `storage.s3.public-base-url`에 CloudFront 배포 주소를 넣고, 버킷은 Origin Access Control 등을 이용해 직접 접근을 차단합니다.

## 3. 환경 변수 / 비밀 관리
| 키 | 설명 | 예시 |
| --- | --- | --- |
| `S3_BUCKET` | 업로드 대상 버킷 이름 | `bootcamp-chat-files` |
| `S3_REGION` | AWS 리전 | `ap-northeast-2` |
| `S3_ENDPOINT` | (옵션) S3 호환 스토리지 사용 시 커스텀 endpoint | `https://s3.ap-northeast-2.amazonaws.com` |
| `S3_PATH_STYLE_ENABLED` | MinIO 등 path-style 필요 시 `true` | `false` |
| `S3_BASE_DIR` | 모든 파일의 루트 디렉터리 | `uploads` |
| `S3_PRESIGN_EXPIRATION_SECONDS` | Presigned URL 유효 시간 | `900` |
| `S3_PUBLIC_BASE_URL` | 이미지/파일 미리보기용 기본 URL | `https://cdn.example.com/uploads` |

IAM 자격 증명은 EC2 Role, ECS Task Role, 또는 AWS Secrets Manager/SSM Parameter Store를 통해 주입하는 것을 권장합니다.

## 4. 네트워크 & 보안
1. **보안 그룹/방화벽**: 백엔드 서버가 S3 endpoint에 접근할 수 있도록 아웃바운드 정책을 허용합니다.
2. **Presigned PUT 보호**: Presigned URL은 토큰 기반이지만, WAF/보안 장비에서 대역폭 제한 및 요청 크기 제한을 설정해 DDoS에 대비합니다.
3. **HTTPS 강제**: 프론트가 Presigned URL을 사용할 때 항상 HTTPS를 사용하도록 `storage.s3.public-base-url` 및 버킷 정책을 구성합니다.

## 5. 모니터링 & 로깅
1. **CloudWatch Metrics**
   - S3 버킷 Request/Bytes, 4xx/5xx
   - Presigned 생성/완료 API의 응답 시간 및 에러율 (Prometheus or CloudWatch Logs)
2. **로그 보관**
   - S3 Access Log 또는 CloudTrail로 PUT/GET/Delete 감사 로그를 저장.
   - Presigned 업로드 세션(`presigned_uploads` 컬렉션) 상태를 모니터링하여 만료 전 완료율을 파악.
3. **알림**
   - 버킷 용량/비용 급증, 4xx/5xx 비율 상승 시 Slack/Email로 알림.

## 6. 정리 작업
1. **Presigned 세션 청소**: Mongo `presigned_uploads` 컬렉션에 TTL 인덱스를 추가하거나 CRON 작업으로 expired/PENDING 상태 문서를 주기적으로 삭제.
2. **고아 파일 제거**: finalize가 호출되지 않은 객체를 식별해 주기적으로 버킷에서 삭제(예: `uploads/tmp/` 영역에서 연령 기반 삭제).
3. **백업/DR**: 필요 시 Cross-Region Replication 혹은 Lifecycle 정책을 통해 장기 보관/아카이브를 설정.

## 7. 배포 체크리스트
1. 환경 변수와 IAM Role이 스테이징/프로덕션에 정확히 주입되었는지 확인.
2. 프런트 도메인에서 Presigned PUT이 정상 동작하는지 실제 브라우저로 테스트.
3. `/api/files/upload`가 fallback 경로(multipart)도 지원하므로, 필요 시 로컬 개발자는 `.env`에 `S3_BUCKET`만 지정하면 됩니다.
4. CDN/프록시가 `/api/files/download/*`, `/api/files/view/*` 경로를 캐시/보호할 수 있도록 헤더 정책을 점검.

이 가이드를 바탕으로 인프라 팀은 S3 기반 Presigned 업로드 플로우를 안정적으로 운영할 수 있습니다.
