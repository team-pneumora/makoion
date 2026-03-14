# 현재 진행 상태 및 로드맵

기준 날짜: 2026-03-13

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
  - Kotlin + Jetpack Compose
  - Chat / Dashboard / History / Settings 제품 셸
  - 파일/기기 운영 콘솔은 Settings 내부 고급 도구로 유지하고 기본은 접힘 상태
  - Chat에서 quick-start prompt로 refresh / summarize / organize / dashboard routing / companion `health` / `session.notify` / allowlisted `workflow.run` / companion-open turn을 직접 시작 가능
  - companion target selection은 기본적으로 최신 Direct HTTP device를 자동 선택하고, Settings에서 특정 device를 탭하면 pin, 다시 탭하면 auto-select로 복귀
  - Chat assistant message에서 현재 turn task를 다시 링크해 linked task/approval context, follow-up note, inline approve/deny/retry action을 직접 surface
  - health / `session.notify` / `workflow.run` / transfer / companion-open task context의 후속 action이 다시 chat turn으로 들어가도록 정리해서, Chat 안에서 연속 조작이 task runtime을 계속 통과하도록 맞춤
  - Settings bridge controls의 direct remote-action 버튼은 진단용 보조 경로로만 남기고 추가 접힘 아래로 내려 chat-first 기본 흐름을 유지
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
  - stale `Sending` draft recovery
  - retryable direct HTTP/network failure에 대한 per-draft backoff + delayed retry scheduling
  - Settings 고급 도구와 Chat turn에서 companion `health` probe 실행 가능
  - Settings 고급 도구와 Chat turn에서 `session.notify` probe 전송 가능
  - Settings 고급 도구와 Chat turn에서 `app.open` probe 전송 가능
  - Devices 탭에서 `app.open` target을 `inbox`, `latest_transfer`, `actions_folder`까지 확장
  - Settings 고급 도구와 Chat turn에서 allowlisted `workflow.run` seed 전송 가능
  - Devices 탭 remote action 버튼을 advertised capability snapshot 기준으로 gating
  - foreground 진입 시 approval/device/organize/audit refresh와 transfer recovery를 다시 거는 shell recovery coordinator 추가
  - Devices 탭 Bridge controls에서 shell recovery 상태, trigger, 최근 성공/실패 요약을 바로 볼 수 있게 보강
  - `Refresh device state`가 동일한 shell recovery 경로를 타도록 정리
  - manual shell recovery 성공/실패를 audit trail에도 남기도록 보강
  - transfer outbox drain completed / worker retry / worker failure를 transport audit trace에 남기도록 보강

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
  - desktop shell 기반 best-effort `inbox` / `latest_transfer` / `actions_folder` open
  - allowlisted desktop workflow `open_latest_transfer`, `open_actions_folder` seed 실행

---

## 2. 현재 확인된 동작

### 로컬 검증 완료

- `projects/core` / `projects/db` / `projects/model_router`
  - analyze/test 통과
- `projects/apps/android`
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
    - `app.open` `best_effort` open + audit `opened`
      - `inbox`
      - `latest_transfer`
      - `actions_folder`
    - `workflow.run` `record_only`
      - `open_latest_transfer`
      - `open_actions_folder`
    - `workflow.run` `best_effort` execute + audit `completed`
      - `open_latest_transfer`
      - `open_actions_folder`
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
- `projects/apps/desktop-companion`
  - `javac` 컴파일
  - `/health` 응답 확인
  - manifest POST 확인
  - zip POST 확인
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

- background/task recovery 의 장시간 런타임 검증
  - `scripts/validate-shell-recovery.ps1` 기준 stale/due/delayed retry 복구는 실기기 통과
  - `scripts/validate-shell-lifecycle-recovery.ps1` 기준 process death / background-resume baseline은 실기기 통과
  - `scripts/validate-shell-recovery-soak.ps1` 기준 short combined smoke loop 2회도 실기기 통과
  - Devices 탭 shell recovery 상태 노출은 반영 완료
  - manual shell recovery audit trail 기록은 반영 완료
  - Approvals 탭 상단 `Jump to audit trail` 바로가기로 recovery/audit 확인 동선 보강 완료
  - transport drain summary / worker retry audit는 반영 완료
  - 남은 범위는 multi-hour background / foreground resume / process death soak 장시간 run
- Direct HTTP pull-based recovery / unresolved payload fallback 후속 검증
---

## 3. 현재 제품 상태를 한 줄로 요약하면

“폰 호스팅 개인 AI agent 서버의 Android MVP shell은 실기기 기준 핵심 자원 연결/실행 경로를 통과했고, desktop companion는 files.transfer 다음으로 session.notify, app.open, workflow.run seed까지 붙은 상태다. 이제 우선순위는 foreground/task recovery를 장시간 기준으로 단단하게 만들고, 원격 세션 액션을 실사용 UX와 chat-first 흐름 기준으로 다듬는 것이다.”

---

## 4. 다음 우선순위

### 우선순위 1

background refresh / task recovery 고도화

- foreground 진입 시 refresh/recovery coordinator를 기준으로 stale state 자동 회복 강화
- 앱 재시작 후 in-flight task 복원과 approval / transfer / organize 최신 상태 동기화 검증
- 장시간 대기 후 foreground 복귀 시 transfer retry / audit / organize pending state가 정상 재표면화되는지 확인

### 우선순위 2

Phase 2 원격 세션 액션 확장 및 하드닝

- `workflow.run` allowlist 확장과 `app.open` 후속 target 유형 정리
- action materialization / audit trail / capability surface를 `files.transfer`와 같은 기준으로 정리

### 우선순위 3

delete consent 및 organize edge case 고도화

- source delete 불가 케이스에 대한 UI/감사 표시 개선
- SAF / MediaStore source별 결과 차이 정리

### 우선순위 4

Direct HTTP failure recovery 및 대용량 후속 전략

- retry scheduled draft가 companion 복구 이후 정상 drain 되는지 실기기 기준 재검증
- partial receipt / malformed receipt / timeout / delayed_ack 케이스의 실기기 캡처 축적
- pull-based companion recovery 또는 파일별 재개 단위가 필요한지 검토

---

## 5. 다음 구현 때 참고할 진입점

- Android shell 상태/흐름: `projects/apps/android/app/src/main/java/io/makoion/mobileclaw/ui/`
- Android data 계층: `projects/apps/android/app/src/main/java/io/makoion/mobileclaw/data/`
- Desktop companion seed: `projects/apps/desktop-companion/src/io/makoion/desktopcompanion/Main.java`
- 프로젝트 전체 요약: `CLAUDE.md`
- 기술 결정: `docs/architecture/decisions.md`
