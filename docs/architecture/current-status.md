# 현재 진행 상태 및 로드맵

기준 날짜: 2026-03-28

이 문서는 Makoion(현 코드베이스명 MobileClaw)의 현재 구현 범위, 검증 상태, 남은 우선순위를 한 곳에서 빠르게 확인하기 위한 상태 문서다.

---

## 1. 지금까지 완료된 범위

### Phase 0

- `projects/core` 완료
  - 도메인 모델, 액션 인텐트, 태스크 상태머신, 정책/승인/감사 인터페이스
  - capability registry, pairing/session 모델, JSON 직렬화, 기본 구현 포함
- `projects/db` 완료
  - SQLite schema, migrations, repository contracts
- `projects/model_router` 완료
  - provider/auth profile/router/failover 기본 구현
- 검증 완료
  - `dart analyze`
  - `dart test`

### Phase 1 Android Shell

- `projects/apps/android` 네이티브 셸 부트스트랩 완료
  - chat-first agentic runtime foundation 추가
    - `AgentEnvironmentSnapshot` 기반 capability inventory 도입
    - market news / morning briefing / email triage / Telegram connect goal recipe planner foundation 추가
    - scheduled automation record가 `run spec / blocked reason / last result / delivery receipt`를 저장하도록 확장
    - `ScheduledAutomationCoordinator`가 실제 scheduled agent runner + delivery router를 통해 briefing/news 결과를 생성하고 전달하도록 전환
    - Telegram delivery channel은 secret을 Android Keystore에, chat binding / delivery receipt는 SQLite에 저장하는 구조로 확장
    - chat에서 `connect telegram token <BOT_TOKEN> chat <CHAT_ID>` 형식으로 Telegram relay를 연결하고, test send 성공 뒤에만 활성화하도록 고도화
    - market news watcher는 public market feed fallback으로 headline을 수집하고 A/B/C impact를 분류하는 v1 path를 가짐
    - email triage는 generic IMAP mailbox connector 기준으로 chat에서 직접 연결 가능하고, scheduled triage run이 promotional move / important alert / review queue 를 실제로 수행
    - mailbox credential은 Android Keystore에 저장되고, mailbox profile / triage result는 SQLite에 영속화되어 Dashboard inspection surface에 중요 메일 / review queue 로 남음
    - email 관련 질문은 provider freeform fallback으로 흘리지 않고, 현재 mailbox 상태와 연결 명령 형식을 chat 안에서 직접 설명
  - Kotlin + Jetpack Compose
  - Chat / Dashboard / History / Settings 제품 셸
  - 파일/기기 운영 콘솔은 Settings 내부 고급 도구로 유지하고 기본은 접힘 상태
  - Chat 탭은 transcript + 단일 composer 중심으로 재정리되어, 상단 상태 카드 / quick prompt rail / 별도 inline action summary card 없이 대화 안에서 turn follow-up 과 approval/retry 를 처리
  - composer 는 숨겨진 `Add` 메뉴로 사진/동영상 라이브러리, 파일 루트 attach 진입점을 모으고 voice 는 전용 compact control 로 유지
  - fresh install / cleared state 에서는 기본 tutorial seed message 를 더 이상 깔지 않고, empty chat entry copy 만 남겨 첫 진입 인상을 단순하게 유지
  - fresh entry empty-state 는 단일 `초기 설정` starter chip 중심으로 유지하고, setup 질문은 resource stack dump 대신 first-run step answer 로 응답
  - 앱 시작 시 setup 미완료 상태면 메인 shell 과 하단 navigation bar 를 렌더링하지 않고 full-screen setup wizard 를 먼저 띄움
  - generic first-turn greeting / capability 소개 응답은 짧은 orientation 톤으로 축약하고 `agent.capabilities.explain` context card 는 Chat 화면에서 숨겨 intro turn 이 설명서처럼 보이지 않게 정리
  - Chat에서 quick-start prompt로 refresh / summarize / organize / dashboard routing / companion `health` / `session.notify` / allowlisted `workflow.run` / companion-open turn을 직접 시작 가능
  - companion target selection은 기본적으로 최신 Direct HTTP device를 자동 선택하고, Settings에서 특정 device를 탭하면 pin, 다시 탭하면 auto-select로 복귀
  - Chat assistant message에서 현재 turn task를 다시 링크해 linked task/approval context, follow-up note, inline approve/deny/retry action을 직접 surface
  - health / `session.notify` / `workflow.run` / transfer / companion-open task context의 후속 action이 다시 chat turn으로 들어가도록 정리해서, Chat 안에서 연속 조작이 task runtime을 계속 통과하도록 맞춤
  - companion `health` / `session.notify` / `app.open` / `workflow.run` 결과를 linked task summary로 더 촘촘하게 저장해서, 앱 재진입 후에도 chat context card가 실제 action detail / timing / follow-up guidance를 복원
  - chat context card의 companion continuation 버튼을 성공/실패/자원 대기 상태별로 공통 규칙으로 묶어서, 원격 session action 실패 후에도 Settings 복귀나 재시도 prompt가 같은 흐름으로 이어지도록 정리
  - Settings bridge controls의 direct remote-action 버튼은 진단용 보조 경로로만 남기고 추가 접힘 아래로 내려 chat-first 기본 흐름을 유지
  - Settings 첫 화면은 `Initial setup` 카드가 맨 위에 오고, `AI model provider -> Local files -> Companion (optional)` 순서의 numbered onboarding 으로 바로 따라갈 수 있게 정리
  - first-run entry 는 Toss 스타일의 step-by-step onboarding 흐름으로 재구성되어, `AI provider -> Local files -> Companion(optional) -> Ready`를 한 단계씩 진행하고 완료 전에는 main shell 로 들어가지 않음
  - model provider preset catalog 는 최신 공식 모델 기준으로 정리되어, OpenAI 는 `gpt-5.4 / gpt-5.4-pro / gpt-5-mini / gpt-5-nano / gpt-4.1`, Anthropic 은 `claude-sonnet-4-6 / claude-opus-4-6 / claude-haiku-4-5` 를 기본 노출
  - 기존 설치에서 남아 있던 `gpt-4.x` / `claude-3.x` selected model 은 credential / enabled / default 상태를 보존한 채 최신 supported model 목록으로 자동 정규화
  - 저장된 provider key 다시 보기는 생체인증 또는 기기 잠금 인증을 통과한 뒤에만 허용되고, reveal 은 setup / Settings 입력 필드 안에서만 일시적으로 노출
  - first-run setup / companion continuation / transfer recovery 에서 공용 helper 파일 누락으로 깨져 있던 shared presentation/recovery 계층을 복구하고 관련 단위 테스트를 다시 고정
- 파일 인덱싱 완료
  - MediaStore 인덱싱
  - SAF document tree 연결 및 스캔
- 승인/감사 기본 흐름 완료
  - approval inbox SQLite 영속화
  - audit trail SQLite 영속화
- 음성 엔트리 완료
  - `SpeechRecognizer` 기반 실시간/최종 전사
  - foreground service + quick actions notification
- 파일 그래프 액션 완료
  - preview
  - summarize
  - dry-run organize planning
  - Android share
- organize 실행 1차 완료
  - dry-run organize plan을 approval inbox로 보낼 수 있음
  - 승인 후 MediaStore managed folder로 실제 organize 실행
  - destination copy verification 수행
  - moved / copied-only / delete-consent-required / failed 결과 분리
  - source delete는 Android 권한 허용 범위에서 best-effort, 필요 시 actual Android delete consent launcher로 연결
  - latest organize execution SQLite 영속화 및 앱 재시작 후 복구
  - delete consent pending state 복구
  - delete consent 거절/취소 note 영속화 및 Files 탭 surface 보강
  - Files 탭에서 organize / transfer 성공 판정용 status card 노출
  - debug build에서 delete consent 경로 강제 토글로 회귀 검증 가능
- 기기 페어링/전송 완료
  - pairing session UI
  - paired device selection
  - transfer outbox
  - WorkManager background drain
  - loopback / direct HTTP transport mode
  - transfer receipt 저장
  - receipt_version / acknowledged_at / file counts 기반 receipt validation
  - malformed or partial receipt -> receipt review 상태 표면화
  - device-level validation fault mode (`partial`, `malformed`, `retry once`, `delayed ack`, `timeout once`, `disconnect once`) 제어 추가
  - Direct HTTP device card에서 `adb reverse localhost` / `emulator host` endpoint preset 전환 가능
  - Devices 탭에서 refresh / immediate drain / failed draft requeue 수동 제어 추가
  - Devices 탭에서 next retry, receipt-review issue, transport audit trace 관찰 UI 추가
  - Devices 탭에서 selected companion `/health` probe 결과 확인 가능
  - companion `/health` probe 결과로 paired device capability snapshot 갱신
  - debug build에서 adb-triggerable transport receiver + bootstrap script로 실기기 검증 초기화 가능
  - large or unknown-size payload -> streaming archive upload
  - unresolved payload -> manifest-only fallback
  - companion pending transfer discovery + Android pull-based requeue 로 manifest-only unresolved fallback recovery
  - same `transfer_id` 기준 archive follow-up materialization + placeholder cleanup
  - stale `Sending` draft recovery
  - retryable direct HTTP/network failure에 대한 per-draft backoff + delayed retry scheduling
  - Settings 고급 도구와 Chat turn에서 companion `health` probe 실행 가능
  - Settings 고급 도구와 Chat turn에서 `session.notify` probe 전송 가능
  - Settings 고급 도구와 Chat turn에서 `app.open` probe 전송 가능
  - Devices 탭에서 `app.open` target을 `inbox`, `latest_transfer`, `actions_folder`, `latest_action`까지 확장
  - Settings 고급 도구와 Chat turn에서 allowlisted `workflow.run` seed 전송 가능
    - `open_latest_transfer`
    - `open_actions_folder`
    - `open_latest_action`
  - Devices 탭 remote action 버튼을 advertised capability snapshot 기준으로 gating
  - foreground 진입 시 approval/device/organize/audit refresh와 transfer recovery를 다시 거는 shell recovery coordinator 추가
  - Devices 탭 Bridge controls에서 shell recovery 상태, trigger, 최근 성공/실패 요약을 바로 볼 수 있게 보강
  - `Refresh device state`가 동일한 shell recovery 경로를 타도록 정리
  - manual shell recovery 성공/실패를 audit trail에도 남기도록 보강
  - transfer outbox drain completed / worker retry / worker failure를 transport audit trace에 남기도록 보강
  - 2026-03-25 build에서 approved `files.transfer` task recovery를 durable `transfer_outbox` draft 상태 기준으로 복원하도록 보강
    - shell recovery가 transfer task를 organize retry 경로로 잘못 보내지 않음
    - chat/runtime transfer task action key를 approval intent/action coordinator와 동일한 `files.transfer.execute` 로 정렬

### Phase 2 Seed Desktop Companion

- `projects/apps/desktop-companion` 최소 companion endpoint 완료
  - `GET /health`
  - `POST /api/v1/transfers`
  - `POST /api/v1/session/notify`
  - `POST /api/v1/app/open`
  - `POST /api/v1/workflow/run`
  - manifest-only 요청 materialization
  - zip / streaming archive payload extraction
  - receipt metadata 응답
  - `session.notify` request materialization (`request.json`, `summary.txt`)
  - `app.open` request materialization (`request.json`, `summary.txt`)
  - `workflow.run` request materialization (`request.json`, `summary.txt`)
  - system tray 기반 best-effort desktop notification 표시
  - desktop shell 기반 best-effort `inbox` / `latest_transfer` / `actions_folder` / `latest_action` open
  - allowlisted desktop workflow `open_latest_transfer`, `open_actions_folder`, `open_latest_action` seed 실행

---

## 2. 현재 확인된 동작

### 로컬 검증 완료

- `projects/core` / `projects/db` / `projects/model_router`
  - analyze/test 통과
- `projects/apps/android`
  - `:app:testDebugUnitTest`
  - `:app:assembleDebug`
  - emulator `emulator-5554`
    - debug APK reinstall
    - launcher smoke
  - `:app:testDebugUnitTest` 통과
  - `:app:compileDebugKotlin`
  - `:app:assembleDebug`
  - 실기기 Samsung Android 기기에서 debug APK 설치 확인
  - 실기기에서 organize dry-run -> approval -> execution 검증 완료
    - 결과: `8 moved`, `Verified 8 destination copies`
  - 실기기에서 debug `Force delete consent path` -> Android delete consent 팝업 -> `허용` 후 `8 moved` 복귀 확인
  - 실기기에서 debug `Force delete consent path` -> Android delete consent `거절/취소` 후 `0 moved, 8 awaiting delete consent` 상태 및 재시작 후 pending 복구 확인
  - 실기기에서 앱 재시작 후 Files 상단 안내, workflow snapshot, latest organize execution card에 delete consent `거절/취소` note 와 `Request Android delete consent (8)` 재표면화 확인
  - 실기기에서 앱 재시작 후 latest organize execution 복구 확인
  - 실기기에서 Direct HTTP `adb reverse` endpoint + `/health` probe + delivery success 확인
  - 실기기에서 voice quick entry transcript 및 recent captures 누적 확인
  - 실기기에서 quick actions notification 표시 및 app-open 진입 확인
  - 실기기에서 quick actions notification `Voice` / `Approvals` 버튼 실행 기록 확인
  - 실기기에서 `Devices > Bridge controls` 의 `Shell recovery passed / Manual / Foreground` 확인
  - `:app:assembleDebug` 재빌드 후 foreground recovery coordinator 포함 APK 재설치 확인
  - `scripts/bootstrap-transport-validation.ps1` + `adb reverse` bootstrap 확인
  - debug-only cleartext network security config 적용 후 paired device `/health` probe 성공 확인
  - `scripts/validate-direct-http-drafts.ps1` 로 synthetic manifest-only Direct HTTP draft send 검증 완료
    - `normal`
    - `partial_receipt`
    - `malformed_receipt`
    - `retry_once`
    - `timeout_once`
    - `disconnect_once`
    - `delayed_ack` 첫 실패 후 `normal` recovery
  - `scripts/validate-direct-http-archives.ps1` 로 실제 archive payload Direct HTTP send 검증 완료
    - `archive_zip`
      - 2 x 64 KiB payload
      - companion `files/`, `manifest.json`, `summary.txt`, `received.txt` materialization 확인
    - `archive_zip_streaming`
      - 1 x 18 MiB payload
      - companion `files/`, `manifest.json`, `summary.txt`, `received.txt` materialization 확인
  - `scripts/validate-direct-http-archive-faults.ps1` 로 실제 archive payload Direct HTTP fault mode 검증 완료
    - `archive_zip`
      - `normal`
      - `partial_receipt`
      - `malformed_receipt`
      - `retry_once`
      - `timeout_once`
      - `disconnect_once`
      - `delayed_ack`
    - `archive_zip_streaming`
      - `normal`
      - `partial_receipt`
      - `malformed_receipt`
      - `retry_once`
      - `timeout_once`
      - `disconnect_once`
      - `delayed_ack`
  - `scripts/validate-direct-http-companion-actions.ps1` 로 실기기 companion action 검증 완료
    - `session.notify` materialization + system tray display + audit `delivered`
    - `app.open` `record_only` materialization + audit `recorded`
      - `inbox`
      - `latest_transfer`
      - `actions_folder`
      - `latest_action`
    - `app.open` `best_effort` open + audit `opened`
      - `inbox`
      - `latest_transfer`
      - `actions_folder`
      - `latest_action`
    - `workflow.run` `record_only`
      - `open_latest_transfer`
      - `open_actions_folder`
      - `open_latest_action`
    - `workflow.run` `best_effort` execute + audit `completed`
      - `open_latest_transfer`
      - `open_actions_folder`
      - `open_latest_action`
  - `scripts/validate-shell-recovery.ps1` 로 실기기 shell recovery 검증 완료
    - stale `Sending` draft -> manual shell recovery -> `Delivered`
    - due retry draft -> immediate drain 재기동 -> `Delivered`
    - delayed retry draft -> queued 유지 -> scheduled retry 후 `Delivered`
  - `scripts/validate-shell-lifecycle-recovery.ps1` 로 실기기 lifecycle recovery 검증 완료
    - stale `Sending` draft -> force-stop / relaunch -> `Delivered`
    - due retry draft -> background / foreground resume 후 `Delivered`
    - delayed retry draft -> force-stop / relaunch -> scheduled retry 후 `Delivered`
  - `scripts/validate-shell-recovery-soak.ps1` 로 실기기 short soak smoke 검증 완료
    - manual recovery + lifecycle recovery combined 2 iterations -> 모두 `통과`
    - foreground due retry 시나리오는 background-first queue + foreground resume 으로 race 없이 고정
  - 2026-03-14 build에서 validation cleanup 경로 추가 후 `scripts/validate-shell-recovery-soak.ps1` 8 iterations 재검증 통과
    - validation device / pairing session / transfer draft cleanup가 각 run 종료 후 수행됨
    - paired device / pairing session / transfer outbox count가 더 이상 증가하지 않음
  - 2026-03-14 `scripts/validate-shell-recovery-soak.ps1` 30분 duration rerun 통과
    - `19 iterations`, `37 checks`, `0 failures`
    - 이전 stale sending manual recovery false-negative 원인이던 debug command auto-open foreground recovery race를 `validate-shell-recovery.ps1` 쪽에서 차단
  - 2026-03-25 `emulator_host` preset 기준 `scripts/validate-shell-recovery.ps1`, `scripts/validate-shell-lifecycle-recovery.ps1` 재검증 통과
    - delayed retry queued 판정은 `next_attempt_at > created_at` 기준으로 안정화되어 에뮬레이터 시간 오차에도 false-negative가 나지 않음
    - bootstrapped Direct HTTP device 확인 실패 시 manual/lifecycle validation 스크립트가 bootstrap 자체를 재시도하고 recent device snapshot을 같이 남김
  - 2026-03-25 `scripts/validate-shell-recovery-soak.ps1` 120분 duration rerun 이 emulator `emulator_host` Direct HTTP 환경에서 통과
    - `34 iterations`, `68 checks`, `0 failures`
    - bootstrap 장기 반복 중 드물게 나오던 Android package/activity service race는 `bootstrap-transport-validation.ps1` 의 readiness wait + install/broadcast/activity retry로 흡수됨
  - 2026-03-25 `scripts/validate-direct-http-pull-recovery.ps1` 로 emulator `emulator_host` Direct HTTP unresolved payload fallback recovery 검증 완료
    - 최초 전송은 `manifest_only` receipt + companion pending placeholder 로 기록됨
    - on-device source materialization 뒤 동일 `transfer_id` 가 `archive_zip` 로 재전송되고 companion pending 목록에서 제거됨
    - same transfer directory 아래 실제 `files/` materialization 과 `requested-files/` cleanup 확인
- 2026-03-26 companion remote action task continuity 정리 후 `:app:testDebugUnitTest`, `:app:assembleDebug`, `scripts/validate-direct-http-pull-recovery.ps1 -EndpointPreset emulator_host -Port 8806` 재검증 통과
  - companion action key / follow-up 규칙이 runtime/UI 공통 helper로 정리됨
  - chat context card가 성공/실패/자원 대기 상태에서 다음 prompt를 다르게 제안하고, persisted task summary에 remote action detail / timing 이 남음
- 2026-03-26 companion remote action hardening 후 `:app:testDebugUnitTest`, `:app:assembleDebug`, `scripts/validate-direct-http-companion-actions.ps1 -Serial emulator-5554 -EndpointPreset emulator_host -Port 8807`, `scripts/validate-direct-http-pull-recovery.ps1 -Serial emulator-5554 -EndpointPreset emulator_host -Port 8808` 재검증 통과
  - chat quick-start 와 linked-task continuation 이 shared prompt catalog 를 같이 써서 companion follow-up prompt wording drift 를 제거
  - `workflow.run` / `app.open` linked task summary 가 stable workflow id / target kind 를 남겨 앱 재진입 후에도 continuation routing 이 같은 규칙으로 복원됨
  - companion action validation script 가 Android debug `debug-validation-state.json` 을 직접 읽도록 정리되어 local Python / SQLite dependency 없이 emulator 검증 가능
  - 같은 날 `scripts/validate-direct-http-companion-actions.ps1 -Serial emulator-5554 -EndpointPreset emulator_host -Port 8809 -AppOpenMode best_effort -WorkflowRunMode best_effort` 도 재통과해서 record-only 뿐 아니라 best-effort remote action open/run 경로까지 확인
  - 2026-03-25 `scripts/validate-direct-http-drafts.ps1` full rerun 이 emulator `emulator_host` Direct HTTP 환경에서 재통과
    - `normal`
    - `partial_receipt`
    - `malformed_receipt`
    - `retry_once`
    - `timeout_once`
    - `disconnect_once`
    - `delayed_ack`
    - delayed_ack / timeout_once 의 stale sending or delayed retry false-negative 는 validation script 와 delayed ack debug timing 조정으로 흡수됨
- 2026-03-27 chat-first shell declutter 후 `:app:testDebugUnitTest`, `:app:assembleDebug`, emulator `emulator-5554` install / launch / screenshot smoke 재검증 통과
  - Chat 탭이 message history + single composer 구성으로 단순화되어 상단 상태 surface 와 composer quick prompts 가 제거됨
  - approval / retry / continuation affordance 는 linked task chat context card 안으로만 남겨 chat 내부 decision flow 로 수렴
  - narrow phone width 에서 `Voice` 버튼이 줄바꿈되던 레이아웃 문제는 icon-only compact control 로 수정
- 2026-03-27 initial chat impression polish 후 `:app:testDebugUnitTest`, `:app:assembleDebug`, emulator `emulator-5554` fresh-entry / first-greeting screenshot smoke 재검증 통과
  - fresh entry 에서 legacy seeded welcome/guidance message pair 가 제거되어 바로 clean chat entry state 로 시작
  - `hello` 류 첫 턴 응답이 짧은 orientation 문장으로 바뀌고, intro turn 에서는 planner/task context card 가 숨겨져 첫 화면이 과도하게 기술적으로 보이지 않음
  - task/approval context chip row 는 horizontal scroll 로 바뀌어 좁은 폭에서 badge text 가 세로로 붕괴하지 않음
- 2026-03-27 chat onboarding clarity pass 후 `:app:testDebugUnitTest`, `:app:assembleDebug`, emulator `emulator-5554` reinstall / `pm clear` / fresh-entry screenshot smoke 재검증 통과
  - empty chat entry 가 `한 문장으로 시작하세요` onboarding card 로 바뀌어, 첫 요청 위치와 model API key 설정 위치를 한 화면에서 설명
  - starter affordance 는 chat 안의 1-2개 prompt chip 으로 제한되어 예전 capability dump 나 별도 quick-rail 없이도 다음 행동을 유도
  - untouched primary thread 의 assistant-only intro residue 를 자동 정리해 업그레이드 뒤에도 오래된 verbose intro message 가 재표면화되지 않음
  - simple routing / resource-summary success turn 은 planner/context card 를 숨겨 answer 본문만 남기고, approval / retry / remote-action 같은 운영 task 에만 context chrome 를 유지
  - emulator Korean IME composition overlay 때문에 adb 기반 prompt-entry smoke 는 다소 불안정했지만, fresh-entry visual state 와 chrome suppression 은 확인
- 2026-03-28 first-run setup stabilization 후 `:app:testDebugUnitTest`, `:app:assembleDebug`, emulator `emulator-5554` reinstall / `pm clear` / fresh-entry + Settings screenshot smoke 재검증 통과
  - Chat 첫 화면은 단일 `초기 설정` starter chip 과 짧은 안내문만 남기고, 첫 실행 사용자가 바로 Settings 초기 설정으로 진입할 수 있게 정리
  - Settings 는 `Initial setup` 헤더 아래에서 `API key 저장 -> local file access 허용 -> companion pairing(optional)` 순서를 한 화면에서 따라갈 수 있게 보강
  - `initial setup`, `api key`, `어떻게 시작` 류 질문은 더 이상 resource stack summary 로 빠지지 않고 setup 전용 답변 + Settings 라우팅으로 처리
  - 누락돼 있던 shared helper 파일 복구로 chat continuation / remote action summary / transfer recovery 관련 unresolved reference 빌드 실패를 제거
  - emulator Korean IME 때문에 prompt-entry automation 은 불안정했지만, fresh-install visual state 와 Settings first-run flow 는 확인
- 2026-03-28 startup gating pass 후 `:app:testDebugUnitTest`, `:app:assembleDebug`, emulator `emulator-5554` reinstall / `pm clear` / first-run wizard screenshot smoke 재검증 통과
  - setup 미완료 상태에서는 앱 시작 즉시 bottom navigation 이 없는 full-screen wizard 가 먼저 뜨고, 메인 shell 은 렌더링되지 않음
  - wizard 는 `1 / 4 AI provider`, `2 / 4 Local files`, `3 / 4 Companion`, `4 / 4 Ready` 구조로 진행되며, 필수 조건은 provider key + local files 두 단계 완료로 고정
  - 기존 Settings 내용은 runtime 콜백을 그대로 재사용하되, first-run 단계에서는 compact wizard 카드와 next/back footer 로만 노출되도록 분리
  - emulator 성능 때문에 cold start 첫 프레임이 느렸지만, 최종 화면에서는 wizard gating 과 no-bottom-nav 상태를 확인
- 2026-03-28 provider model catalog refresh 후 `:app:testDebugUnitTest`, `:app:assembleDebug` 재검증 통과
  - OpenAI preset 이 `gpt-5.4 / gpt-5.4-pro / gpt-5-mini / gpt-5-nano / gpt-4.1` 로 올라가고 `gpt-4.x` 기본 노출은 사실상 `gpt-4.1` 단일 fallback 만 남도록 정리
  - Anthropic preset 이 `claude-sonnet-4-6 / claude-opus-4-6 / claude-haiku-4-5` 로 올라가고 `claude-3.x` 기본 노출은 제거
  - refresh 시 기존 DB profile 의 selected model 이 더 이상 지원되지 않으면 seed default 로 자동 보정되어 setup UI 와 runtime preference 가 같은 최신 catalog 를 보게 됨
- 2026-03-28 biometric credential reveal pass 후 `:app:testDebugUnitTest`, `:app:assembleDebug`, emulator `emulator-5554` reveal smoke 재검증 통과
  - Android Keystore vault 가 저장된 provider secret 을 다시 읽을 수 있게 확장됐지만, UI reveal 은 biometric 또는 device credential 인증 뒤에만 실행
  - setup / Settings 의 eye button 은 입력 중 draft 는 바로 show/hide 하고, 저장된 키는 빈 필드 상태에서 누르면 인증 뒤 reveal 하도록 분기
  - emulator 에는 biometric / device credential 이 미등록이라 성공 reveal 대신 `기기에 등록된 생체인증 또는 기기 잠금이 없습니다.` 안내 문구가 노출되는 fallback 경로까지 확인
- 2026-03-28 provider-backed freeform chat fallback pass 후 `:app:testDebugUnitTest`, `:app:assembleDebug`, emulator `emulator-5554` freeform chat smoke 재검증 통과
  - chat prompt 가 built-in capability intent에 매칭되지 않으면 더 이상 같은 onboarding 문구로 되돌아가지 않고, 저장된 OpenAI / Anthropic provider로 freeform reply 를 생성
  - `AgentTurnContext` 에 recent chat messages 를 연결해 freeform fallback 도 직전 대화 일부를 같이 보고 답하도록 바뀜
  - provider 호출 실패 시에는 generic onboarding 반복 대신 provider / model / key 확인이 필요하다는 오류를 chat bubble 안에서 직접 설명
- 2026-03-28 chat-first MCP guidance / navigation lock pass 후 `:app:testDebugUnitTest`, `:app:assembleDebug`, emulator `emulator-5554` debug APK 재설치 완료
  - agent turn 결과가 더 이상 Chat 밖의 tab 으로 자동 전환되지 않고, Dashboard / History / Settings 는 사용자가 직접 눌렀을 때만 연다
  - 일반적인 `connect / settings / status / mcp` 문구가 broad 라우팅으로 빠지던 규칙을 줄이고, MCP 연결 질문은 chat 안에서 단계형 안내 + follow-up button 으로 이어지게 정리
  - MCP bridge 연결 실패나 resource 대기 상태도 Settings 강제 이동 대신 chat 안에서 다음 단계 버튼으로 이어지도록 정리
- 2026-03-28 Telegram validation / email setup guidance pass 후 `:app:testDebugUnitTest`, `:app:assembleDebug`, emulator `emulator-5554` reinstall / launcher smoke 재검증 통과
  - Telegram delivery 연결은 secret 저장 직후 connected 로 올리지 않고, bot test send 가 성공한 경우에만 binding 을 activate 하도록 변경
  - test send 실패 시 relay 는 staged 로 남고, 마지막 delivery error 가 기록되어 chat 에서 원인과 재시도 포인트를 바로 설명
  - `이메일 자동화 가능해?`, `메일 어떻게 연결해?` 같은 질문은 현재 mailbox 상태와 chat 연결 명령 형식을 직접 설명
- 2026-03-28 mailbox connector / triage pass 후 `:app:testDebugUnitTest`, `:app:assembleDebug`, emulator `emulator-5554` debug APK reinstall / launcher smoke 재검증 통과
  - generic IMAP mailbox connector가 `connect mailbox host <HOST> user <USER> password "<APP_PASSWORD>"` 형식으로 chat에서 직접 연결 가능
  - mailbox validation 성공 시 inbox / promotions folder 를 확인하고 connected 상태로 승격, 실패 시 staged + last error 로 남음
  - email triage automation run 은 최근 inbox mail 을 스캔해 promotional mail 을 지정 보관함으로 이동하고, important mail 은 alert 대상으로, 애매한 mail 은 review queue 로 분류
  - Dashboard 는 mailbox status, important mail, review queue inspection surface 를 추가로 보여주고, organize approval submission 시에는 더 이상 자동으로 Dashboard 로 이동하지 않음
- `projects/apps/desktop-companion`
  - `javac` 컴파일
  - `/health` 응답 확인
  - manifest POST 확인
  - zip POST 확인
  - `GET /api/v1/transfers/pending` 로 pending manifest-only placeholder inventory 응답 확인
  - `scripts/validate-session-notify.ps1` 로 `session.notify` materialization / receipt / desktop notification display 검증 완료
  - `scripts/validate-app-open.ps1` 로 `app.open` target별 materialization / opened receipt 검증 완료
    - `inbox` (`record_only`, `best_effort`)
    - `latest_transfer` (`best_effort`)
    - `actions_folder` (`best_effort`)
  - `scripts/validate-workflow-run.ps1` 로 `workflow.run` materialization / receipt / best-effort desktop open 검증 완료
  - debug receipt mode (`partial_receipt`, `malformed_receipt`, `retry_once`) 응답 확인
  - `scripts/validate-fault-modes.ps1` 로 `timeout_once`, `disconnect_once` 1회 실패 후 재시도 성공 확인
  - `scripts/prepare-adb-reverse.ps1` 로 실기기 USB `adb reverse` 준비 가능

### 아직 부족한 검증

- 없음. 현재 Phase 1 범위에서 정의한 Direct HTTP pull-based recovery / unresolved payload fallback 검증까지 닫힘.
---

## 3. 현재 제품 상태를 한 줄로 요약하면

“폰 호스팅 개인 AI agent 서버의 Android MVP shell은 실기기와 에뮬레이터 기준 핵심 자원 연결, Direct HTTP transfer, manifest-only fallback recovery, 장시간 shell recovery 경로를 통과했고, first-run entry 역시 메인 shell 이전의 explicit setup wizard 기준으로 정리됐다. 이제 우선순위는 photo/video/file/audio 첨부의 실제 turn payload 정교화, background/task recovery 하드닝, organize/delete edge case 정교화다.”

---

## 4. 다음 우선순위

### 우선순위 1

chat attachment payload / ingestion 후속 구현

- 현재 `Add` 메뉴와 voice control 은 chat-first entry point 까지 정리됐지만, 사진/동영상/파일이 실제 per-turn attachment payload 로 흐르는 경로는 아직 staged capability 진입점 수준
- photo/video/file/audio 를 동일한 chat turn 문맥에 담아 planner/runtime/audit 까지 일관되게 넘기는 ingestion 모델이 다음 구현 범위
- attachment preview / removal / upload progress 없이도 MVP 로 동작할 최소 turn payload surface 정의가 필요

### 우선순위 2

background refresh / task recovery 후속 하드닝

- foreground 진입 시 refresh/recovery coordinator 를 기준으로 stale state 자동 회복 강화
- 앱 재시작 후 in-flight task 복원과 approval / transfer / organize 최신 상태 동기화 검증 확대
- duration-based soak harness를 다른 task/recovery 조합까지 확장

### 우선순위 3

delete consent 및 organize edge case 고도화

- source delete 불가 케이스에 대한 UI/감사 표시 개선
- SAF / MediaStore source별 결과 차이 정리

### 우선순위 4

Phase 2 원격 세션 액션 확장 및 하드닝

- `workflow.run` 추가 allowlist 확장과 `app.open` 후속 target 유형 정리
- action materialization / audit trail / capability surface를 `files.transfer`와 같은 기준으로 정리

---

## 5. 다음 구현 때 참고할 진입점

- Android shell 상태/흐름: `projects/apps/android/app/src/main/java/io/makoion/mobileclaw/ui/`
- Android data 계층: `projects/apps/android/app/src/main/java/io/makoion/mobileclaw/data/`
- Desktop companion seed: `projects/apps/desktop-companion/src/io/makoion/desktopcompanion/Main.java`
- 프로젝트 전체 요약: `CLAUDE.md`
- 기술 결정: `docs/architecture/decisions.md`
