# 커밋 컨벤션

이 저장소의 최근 커밋 이력을 보면 접두어 기반 형식이 반복되며, 문서화된 가이드 없이도 자연스럽게 범주가 정리되어 있습니다. 아래 규칙을 따르면 다음 커밋부터 동일한 스타일을 유지할 수 있습니다.

-## 1. 커밋 유형(타입)
- `feat`: 기능 추가/변경 (`feat: Add base code`)
- `fix`: 버그 수정 (`fix(RoomJoinHandler): null 체크`)
- `docs`: 설명/문서 중심 변경 (`docs: 설명 주석 작성`)
- `perf`: 성능 개선 (`perf(MessageLoader): User N+1 문제 개선`). 역사적으로 `pref`라는 오타가 섞여 있으나 앞으로 `perf`로 통일합니다.
- `style`: 형식/스타일 조정 (기존 `sytle`은 오타로 간주)
- `refactor`: 코드 구조나 리팩터링 (기능/버그 변화 없이 내부 재정리)
- `test`: 테스트 추가/수정 (`test: add load test cases`)
- `ci`: CI/빌드 설정 변경
- `chore`: 기타 유지보수 (예: dependency update, 스크립트 정리)
- `Merge`: GitHub PR 병합 커밋 (예: `Merge pull request #23 ...`)

## 2. 커밋 제목 형식 (Subject)
1. 소문자 타입을 쓰고, 필요하면 괄호로 scope를 붙입니다: `type(scope)`
2. 콜론과 공백 하나 뒤에 설명을 문장형으로 적습니다.
3. PR이나 이슈 번호가 있다면 끝에 `(#번호)` 형태로 덧붙입니다.

예: `perf(MessageLoader): N+1 개선 (#14)`

## 3. Scope 사용 지침
- scope는 변경 대상에 따라 구체적으로 적습니다. 예: `MessageLoader`, `MessageFetchHandler`.
- 백엔드 내 핸들러나 핵심 컴포넌트 이름을 scope로 사용하며, 범위가 애매하면 생략해도 무방합니다.

## 4. 추가 팁
- 본문은 짧게, 제목만으로 의도를 전달하는 것이 목표입니다.
- 문서/분석 변경일 때도 `docs`를 쓰면 무리가 없습니다.
- 과거에 `pref`/`sytle`처럼 오타가 보였으니 새로운 커밋에서는 정확한 철자를 확인해 주세요.

작성일: 최근 커밋 로그(b121df8..e6d0484) 분석 기준.
