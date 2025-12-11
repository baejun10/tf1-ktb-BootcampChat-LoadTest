# FILE_CACHE_OPTIMIZATION_2025-12-10

## 개요
- `mapToMessageResponse`가 메시지마다 `fileRepository.findById`를 호출하며 발생하던 File N+1 문제 제거
- Caffeine 기반 `FileCacheService`를 도입해 메시지 로딩 시 파일 메타데이터를 배치로 적재
- 캐시 정책을 설정 파일(`application.properties`)과 전용 Properties 클래스에서 관리하도록 구조화

## 문제 진단
- `/apps/backend/src/main/java/com/ktb/chatapp/websocket/socketio/handler/MessageResponseMapper.java` 내부에서 메시지마다 개별 파일 조회
- 방 입장/퇴장 시스템 메시지도 동일 매퍼를 사용하면서 N+1 패턴 반복
- MongoDB 기준으로 메시지 30개 로드시 최대 30회 파일 쿼리가 발생하여 레이턴시 증가

## 적용한 개선
1. **캐시 인프라**
   - `@EnableCaching`을 전용 `CachingConfig`에 선언하고 `FileCacheProperties`를 `@ConfigurationProperties`로 등록
   - `FileCacheConfig`에서 `LoadingCache<String, Optional<File>>` 빈을 생성 (사이즈/TTL은 프로퍼티 주입)
   - `application.properties`에 `cache.file.maximum-size`, `cache.file.expire-after-access` 기본값 추가

2. **FileCacheService**
   - `Caffeine` `LoadingCache`를 주입받아 `getFiles(Collection<String>)`, `getFile(String)` 제공
   - `loadAll` 구현으로 중복 없는 파일 ID 집합을 MongoDB 한 번의 `findAllById`로 조회 → 캐시에 저장

3. **매퍼 호출부 수정**
   - `MessageLoader`에서 메시지 순회 시 파일 ID를 수집하고 `fileCacheService.getFiles()`로 일괄 조회
   - `mapToMessageResponse(message, user, file)` 시그니처 추가, RoomJoin/RoomLeave 핸들러에서도 캐시를 통해 파일 전달
   - 매퍼는 더 이상 레포지토리를 직접 호출하지 않으며, File 정보가 없는 경우 null 처리

## 테스트 & 검증
- `./mvnw -q -DskipTests compile`로 컴파일 단계 확인
- `./mvnw test` 전체 통과 (캐시 프로퍼티 빈 등록 문제 발견 → `@EnableConfigurationProperties(FileCacheProperties.class)` 복구 후 통과)
- MessageLoaderTest와 RoomJoin/Leave 시나리오에서 `FileCacheService`가 주입되는지 확인

## 영향 및 기대효과
- 메시지 페이징 조회 시 파일 정보가 필요한 경우에도 쿼리 수가 1회로 제한 → Mongo round-trip 감소
- 동일 파일이 여러 메시지에서 참조될 때 캐시 히트율 향상으로 응답 지연 감소 기대
- 캐시 정책을 설정 파일로 외부화하여 환경별 튜닝 가능

## 추후 과제
- 캐시 통계(`recordStats`)를 기반으로 Prometheus/Grafana에 히트율/eviction 지표 노출
- 파일 메타데이터가 더 이상 필요 없을 때 캐시를 비우는 훅 또는 TTL 조정 검토
- `$lookup` 기반 Mongo aggregation 도입 여부 재검토 (대규모 메시지 페이지에서 추가 최적화 가능성)
