# Session Validation & Caching Strategy

## 배경
- 모든 소켓 이벤트가 `SessionService#validateSession` 를 호출하면서 `sessionStore.findByUserId` 로 MongoDB round-trip 이 발생했다(TODO 42).
- 부하 테스트 시 해당 I/O 가 TPS 병목이 되어 세션 검증이 전체 처리량을 낮출 위험이 있었다.

## 현재 대응
1. **로컬 캐시 도입**  
   - `SessionService` 내부에 `ConcurrentHashMap<String, CachedSession>` 을 두고 `findSessionWithCache` 로 세션을 1초 간 재사용한다.
   - 동일 인스턴스에 붙은 사용자 요청은 메모리 hit 로 처리되어 대부분의 이벤트가 Mongo 접근 없이 끝난다.
   - 세션 갱신/삭제 시 `cacheSession`·`evictCachedSession` 으로 캐시와 저장소를 동기화해 TTL 연장, 삭제 등 상태 변화를 즉시 반영한다.

2. **Sticky Session 전제**  
   - 현재 인프라는 sticky session 으로 구성되어 있어 동일 사용자의 요청이 항상 같은 서버 인스턴스에 도달한다.
   - 덕분에 로컬 캐시만으로도 일관성과 성능을 동시에 확보할 수 있으며, MongoDB 를 글로벌 캐시 없이 사용해도 TPS 저하가 없다.

## MongoDB 선택 이유
- 세션이 사용자당 단일 문서로 표현되어 MongoDB 의 document 모델과 TTL 인덱스로 관리하기 쉽다.
- 이미 서비스의 주요 스토리지로 MongoDB 를 운영 중이라, 추가 Redis 인프라 없이도 영속성과 장애 대응을 확보할 수 있다.

## Redis 미도입 사유
- sticky session 환경에서는 인스턴스 로컬 캐시만으로 세션 검증의 대부분을 처리하며, MongoDB 는 영속 저장소 역할만 수행하므로 Redis 를 추가할 필요성이 낮다.
- Redis 를 도입하면 운영·모니터링 대상이 늘어나 부하 테스트 타임라인에 부담이 된다.

## 향후 전환 조건
- 로드밸런서 정책 변경으로 sticky session 이 깨지거나, 외부 시스템에서 다양한 인스턴스의 세션을 동시에 조작해야 하는 경우에는 Redis(또는 다른 분산 캐시)를 도입해 세션 상태를 공유해야 한다.
- 인스턴스 재시작·스케일링 시 최초 요청이 Mongo 로 향하는 순간적 병목이 문제가 된다면 TTL 조정이나 분산 캐시 도입을 검토한다.

## 결론
- 현재 부하 테스트 환경에서는 **MongoDB + 로컬 캐시** 조합으로 성능 목표를 달성할 수 있다.
- 다만 인프라 조건이 바뀌면 Redis 기반 분산 세션 저장소나 pub/sub 기반 캐시 무효화 전략으로 확장할 여지를 열어둔다.
