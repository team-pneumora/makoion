# 2026-03-28 Mailbox Connector And Chat-Only Execution Hardening

## Goal

chat-first agent runtime에서 남아 있던 실제 blocker 두 개를 닫는다.

- generic IMAP mailbox connector를 chat만으로 연결하고, email triage vertical을 실제로 실행 가능하게 만든다.
- 승인/작업 생성 흐름에서 아직 남아 있던 Dashboard 자동 이동을 제거해, 사용자가 직접 누르기 전까지는 Chat에 머물게 만든다.

## Assumptions

- Android MVP의 mailbox connector는 OAuth가 아니라 `generic IMAP + app password` 기준으로 먼저 완성한다.
- secret 저장은 Android Keystore 기반 vault를 사용한다.
- 광고성 메일 이동은 reversible action 이므로 자동 수행하되, 삭제는 하지 않고 지정 보관함으로만 이동한다.
- 중요 메일은 local push 를 기본으로 하고, Telegram 이 연결돼 있으면 기존 delivery router 를 그대로 fallback 으로 사용한다.

## Task Breakdown

- [x] mailbox profile schema / repository / credential vault 추가
- [x] chat-driven mailbox connect / status command 추가
- [x] environment snapshot / goal planner 에 mailbox connected state 반영
- [x] scheduled email triage runner + heuristic classifier + IMAP move/alert path 구현
- [x] triage result persistence + Dashboard 중요 메일 / review queue surface 추가
- [x] organize approval submission 시 Dashboard 자동 이동 제거
- [x] Android unit/build/emulator 검증

## Validation

- `:app:testDebugUnitTest`
- `:app:assembleDebug`
- emulator `emulator-5554`
  - debug APK reinstall
  - chat launcher smoke
  - mailbox command parsing / dashboard mail queue visual smoke

## Open Risks

- live IMAP validation 은 실제 mailbox host / username / app password 가 있어야 최종 실기 검증까지 닫힌다.
- provider-backed semantic mail classification 없이도 동작하도록 heuristic classifier 를 기본으로 두되, 일부 경계 사례는 review queue 로 남길 수 있다.

# 2026-03-28 Chat-First Agentic Runtime Foundation

## Goal

설정 화면 중심의 현재 shell을 `chat-first agent runtime` 방향으로 실제 전환하기 위한 기반을 추가한다. 이번 구현 범위는 다음 네 축을 우선 닫는다.

- agent가 현재 자원/연결/부족한 요구사항을 구조적으로 읽는 `environment snapshot`
- chat에서 목표를 `goal recipe + task graph`로 읽는 planner foundation
- `Telegram`을 실제 secret/binding 기반 delivery channel로 연결하는 chat-driven path
- scheduled automation이 단순 실행 알림이 아니라 `실제 briefing/news run + delivery`를 수행하는 경로

## Assumptions

- v1 vertical은 `morning briefing`과 `market news watch`를 먼저 완성한다.
- `email triage`는 mailbox connector 부재로 인해 이번 턴에서는 blocked connection task / policy scaffold 수준까지 올린다.
- OpenClaw의 cron/delivery 구조는 참고하되, 정본은 여전히 phone-local DB/task state다.
- 위험한 연결 작업은 chat에서 다음 단계와 요구 secret 형식을 설명하고, 사용자가 secret을 보낸 경우에만 저장한다.

## Task Breakdown

- [x] `AgentEnvironmentSnapshot` / capability inventory 도입
- [x] goal planner foundation + market news / morning briefing / email triage recipe 추가
- [x] delivery channel schema 확장 + Telegram credential vault / chat binding 추가
- [x] Telegram 연결을 `store -> send test -> activate` 검증 체인으로 고도화
- [x] scheduled automation record schema 확장 + run spec / blocked reason / result summary 저장
- [x] scheduled agent runner + delivery router 구현
- [x] `PhoneAgentRuntime`에 chat-driven Telegram connect / state-based goal orchestration 연결
- [x] email capability / blocker를 chat에서 직접 설명하는 setup guide 추가
- [x] unit/build 검증

## Validation

- `:app:testDebugUnitTest`
- `:app:assembleDebug`
- emulator `emulator-5554`
  - debug APK reinstall
  - launcher smoke

## Open Risks

- Telegram 전송은 bot token / chat id가 실제로 준비돼야 live validation까지 닫힌다.
- market news는 public feed fallback을 우선 사용하므로 MCP/browser inventory는 planner 입력으로 반영하되, 수집 자체는 v1에서 public source fallback을 허용한다.

# 2026-03-28 Chat-First MCP Guidance And Navigation Lock

## Goal

chat에서 MCP 연결 흐름을 설명하고 다음 액션까지 이어서 실행할 수 있게 만들고, agent 응답 때문에 Dashboard / History / Settings 탭으로 자동 이동하는 동작을 제거한다.

## Assumptions

- chat-first 원칙에 따라 agent 응답은 기본적으로 현재 Chat 탭에 머문다.
- MCP 연결은 우선 Direct HTTP companion 기반 bridge discovery 경로를 따른다.
- Settings / Dashboard / History 는 사용자가 직접 눌러 들어가는 보조 surface 로 남긴다.

## Task Breakdown

- [x] chat 결과 후 자동 탭 이동 제거
- [x] MCP 일반 질문용 chat guide intent 추가
- [x] MCP 연결 / 상태 / tool / skill sync 후속 버튼 추가
- [x] broad `settings/connect/status` 라우팅 규칙 축소
- [x] Android unit/build 검증
- [x] emulator debug APK 재설치

## Validation

- `:app:testDebugUnitTest`
- `:app:assembleDebug`
- emulator `emulator-5554`
  - debug APK reinstall
  - chat 기반 MCP setup / status / tool follow-up button 노출 확인 준비
  - agent 응답 후 tab 이 자동 변경되지 않는지 수동 smoke 대상

## Open Risks

- 실제 emulator 상호작용 smoke 는 이번 턴에서 APK 재설치까지 완료했고, MCP 경로 자체는 companion 준비 상태에 따라 waiting-resource 로 남을 수 있다.
- Settings / Dashboard / History 내용을 chat bubble 로 더 풍부하게 재구성하는 작업은 아직 최소 범위다.

# 2026-03-28 Provider-Backed Chat Fallback

## Goal

자유 입력 chat prompt가 rule-based intent에 걸리지 않을 때도, 저장된 OpenAI / Anthropic provider를 통해 실제 대화 응답을 생성해서 chat-first 작업 지시 흐름이 끊기지 않게 만든다.

## Assumptions

- 정리 / 전송 / 설정 라우팅 / companion action 같은 기존 built-in capability intent는 그대로 우선한다.
- freeform fallback은 OpenAI / Anthropic 저장 credential이 있을 때만 활성화한다.
- provider 호출이 실패하면 같은 onboarding 문구 반복 대신, key / provider / network 점검이 필요하다는 오류를 chat 안에서 직접 설명한다.

## Task Breakdown

- [x] provider-backed freeform chat fallback 설계
- [x] OpenAI / Anthropic HTTP conversation client 추가
- [x] AgentTurnContext에 recent chat history 연결
- [x] PhoneAgentRuntime fallback을 provider conversation으로 교체
- [x] Android unit/build 검증
- [x] emulator freeform chat smoke 검증

## Validation

- `:app:testDebugUnitTest`
- `:app:assembleDebug`
- emulator `emulator-5554`
  - saved provider key 상태에서 freeform prompt 전송
  - unmatched prompt가 onboarding 반복 대신 provider reply 또는 provider error로 바뀌는지 확인
  - 기존 organize / summarize 등 built-in intent가 그대로 우선되는지 확인

## Open Risks

- 현재 자유 대화 provider는 OpenAI / Anthropic만 구현되어 있고, Gemini만 저장된 상태에서는 built-in intent 외 freeform 대화가 onboarding 안내로 fallback 될 수 있다.
- provider 호출은 최근 chat history 일부만 넣는 lightweight fallback이라, 긴 멀티턴 memory는 아직 transcript 전체 grounding과 다를 수 있다.

# 2026-03-28 Biometric Credential Reveal

## Goal

저장된 model provider key를 그냥 평문으로 다시 노출하지 않고, 생체인증 또는 기기 잠금 인증을 통과한 뒤에만 setup / Settings 화면에서 reveal 할 수 있게 만든다.

## Assumptions

- Android MVP에서는 provider secret 원문을 Android Keystore 기반 vault에서 복호화할 수 있어야 한다.
- reveal 자체는 인증 뒤 사용자 의도에 의해 한시적으로 입력 필드에만 표시한다.
- biometric 가 없으면 `DEVICE_CREDENTIAL` fallback 을 허용하고, 둘 다 없으면 안내 문구로 막는다.

## Task Breakdown

- [x] vault read API 추가
- [x] biometric / device credential prompt helper 추가
- [x] setup provider 카드에 reveal 연결
- [x] Settings provider 카드에 reveal 연결
- [x] Android unit/build 검증
- [x] emulator reveal smoke 검증

## Validation

- `:app:testDebugUnitTest`
- `:app:assembleDebug`
- emulator `emulator-5554`
  - debug APK reinstall
  - `pm clear io.makoion.hub.dev`
  - provider key 저장 후 eye button 이 `저장된 키 보기`로 바뀌는지 확인
  - biometric/device credential 미등록 상태에서 안내 문구 노출 확인

## Open Risks

- 저장된 키가 reveal 되면 현재 세션 입력 필드에는 평문이 보이므로, 사용자는 주변 화면 노출을 직접 관리해야 한다.
- biometric 성공 경로 자체의 자동화 smoke 는 에뮬레이터에 인증 수단이 미등록이라 아직 수동 검증 범위로 남는다.
