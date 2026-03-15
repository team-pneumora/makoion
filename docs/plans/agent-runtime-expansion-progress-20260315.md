# Makoion Agent Runtime Expansion Progress

기준 브랜치: `codex/agent-runtime-plan-20260315`

## 진행 규칙

- 각 단계는 완료 시점에 테스트 결과를 기록한다.
- 커밋과 푸시 결과를 함께 남긴다.
- 실패한 시도도 삭제하지 않고 기록한다.

## 진행 로그

| 날짜 | 단계 | 상태 | 작업 내용 | 테스트 | 커밋/푸시 |
|------|------|------|-----------|--------|-----------|
| 2026-03-15 | Step 0 | 완료 | 사용자 핵심 요구를 바탕으로 agent runtime 확장 계획 문서와 진행 기록 문서를 추가함 | 문서 검토 완료 | 커밋 및 푸시 완료 |
| 2026-03-15 | Step 1 | 완료 | Android Settings에 resource stack을 추가해 `phone -> cloud -> companion -> MCP/API` 우선순위를 제품 UI에 노출함 | `projects/apps/android`: `.\gradlew.bat :app:compileDebugKotlin` 통과 | 커밋 `6c00f1f`, 브랜치 `codex/agent-runtime-plan-20260315` 푸시 완료 |
| 2026-03-16 | Step 2 | 완료 | Android planner contract에 `Answer / Question / Plan / ActionIntent / Escalation` 메타데이터를 도입하고, turn planner 결과를 audit log, task DB, chat/history UI에 구조적으로 남기도록 확장함 | `projects/apps/android`: `.\gradlew.bat :app:compileDebugKotlin` 통과 | 커밋 `96ce8b9`, 브랜치 `codex/agent-runtime-plan-20260315` 푸시 완료 |
| 2026-03-16 | Step 3 | 완료 | Android 셸에 provider profile 저장 구조를 추가하고, `OpenAI / Anthropic / Google Gemini` seed, default/model 선택, Settings UI, turn context model preference, unit test를 도입함 | `projects/apps/android`: `.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin` 통과 | 커밋 `e63bf7f`, 브랜치 `codex/agent-runtime-plan-20260315` 푸시 완료 |
| 2026-03-16 | Step 4 | 완료 | `resource_registry_entries` 저장 구조를 추가하고, phone storage / document roots / cloud drives / companions / AI model providers / MCP/API endpoints를 공통 레코드로 스냅샷 저장하도록 확장함. Settings는 더 이상 즉석 계산 대신 registry DB 스냅샷을 읽는다. | `projects/apps/android`: `.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin` 통과 | 커밋 `373aa4c`, 브랜치 `codex/agent-runtime-plan-20260315` 푸시 완료 |
| 2026-03-16 | Step 5 | 완료 | cloud drive connector skeleton을 추가하고, `Google Drive / OneDrive / Dropbox` seed, staged/mock-ready/reset 상태, Settings 카드, resource registry Priority 2 연동, turn context cloud summary를 도입함 | `projects/apps/android`: `.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin` 통과 | 커밋 `a2f7b66`, 브랜치 `codex/agent-runtime-plan-20260315` 푸시 완료 |
| 2026-03-16 | Step 6 | 완료 | `browser research` skeleton을 planner에 추가하고, research/news/browser 요청을 별도 task 유형으로 기록해 `WaitingResource` 상태와 structured brief를 남기도록 확장함 | `projects/apps/android`: `.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin` 통과 | 커밋 `2e63a0d`, 브랜치 `codex/agent-runtime-plan-20260315` 푸시 완료 |
| 2026-03-16 | Step 7 | 완료 | `scheduled automation` skeleton을 추가하고, 반복/정기 요청을 별도 automation 레코드로 저장해 Dashboard에서 placeholder 상태를 확인하고 `activate/pause` 토글할 수 있게 확장함 | `projects/apps/android`: `.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin` 통과 | 커밋 `8b7a11f`, 브랜치 `codex/agent-runtime-plan-20260315` 푸시 완료 |

## 현재 메모

- 마스터 플랜은 유지하고, 본 문서는 실행 계획과 작업 기록을 담당한다.
- 첫 구현 단계는 `자원 우선순위와 연결 상태를 제품 UI에 명시적으로 보여주는 것`으로 결정했다.
- Step 2 구현 중 첫 컴파일에서는 `AgentTaskRepository.updateTask()`의 인터페이스/구현체 기본값 불일치로 Kotlin 컴파일이 실패했다. 인터페이스에만 기본값을 남기고 구현체에서 제거한 뒤 재컴파일해 해결했다.
- Step 3에서는 credential secret 자체는 저장하지 않고, `provider profile / default route / selected model / credential metadata placeholder`까지만 넣었다. Android secure storage 연동은 후속 단계로 남긴다.
- Step 4에서는 ViewModel이 file/device/provider 변화를 감지해 resource registry snapshot을 DB에 다시 쓰는 구조로 잡았다. 아직 cloud drive 개별 연결 레코드는 없고, Priority 2는 planned placeholder 상태다.
- Step 5에서는 실제 OAuth 대신 `Needs setup -> Staged -> Mock ready` placeholder state만 기록한다. token vault와 실제 cloud file graph 연결은 후속 단계다.
- Step 6에서는 실제 브라우저 executor 없이도 research intent를 별도 task/action key로 분리했다. 이후 browser/MCP/API executor가 붙으면 같은 task line을 이어서 실행할 수 있다.
- Step 7에서는 scheduler worker가 아직 없더라도 반복 요청을 task 이외의 durable automation record로 남기도록 분리했다. 실제 schedule runner와 notification delivery worker는 후속 단계다.
- 다음 구현 후보는 `provider credential vault 연동`과 `MCP/API registry skeleton`이다.
