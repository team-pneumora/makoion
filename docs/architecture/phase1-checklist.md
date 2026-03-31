# Phase 1 체크리스트

Phase 1의 목표: Android shell MVP를 실기기 기준으로 검증 가능한 수준까지 올리고, 핵심 사용자 흐름의 성공/실패 판단 근거를 문서로 남긴다.

기준 날짜: 2026-03-25

---

## 기록 원칙

- [x] 핵심 수동 검증 결과를 이 문서에 남긴다.
- [x] 아키텍처 상태 문서와 우선순위를 함께 갱신한다.
- [x] 실기기 화면 캡처는 로컬 [`results/`](/c:/Users/mombin/OneDrive/Project/Makoion/results)에 보관한다.

---

## 검증 환경

- [x] Android debug build 설치 패키지: `io.makoion.hub.dev`
- [x] 실기기 Android 폰에서 직접 검증
- [x] desktop companion Direct HTTP endpoint를 `adb reverse` 기준 `127.0.0.1:8787`로 연결
- [x] companion transport validation mode는 `Normal receipts` 기준으로 확인

---

## 핵심 사용자 흐름

### 파일 인덱싱 / 계획 / 승인

- [x] `Media access` 권한 허용 후 `Files` 탭에서 최근 파일 인덱싱 확인
- [x] `Plan organize by type` 또는 `Plan organize by source` 실행
- [x] `Dry-run organize plan` 카드에서 대상 파일과 destination root 확인
- [x] `Request organize approval` 실행
- [x] `Approvals` 탭에서 `files.organize.execute` 요청 승인

### organize 실행 / 복구

- [x] 승인 후 organize 실행 완료
  - 기록: `Organize execution finished for 8 files. 8 moved. Verified 8 destination copies.`
- [x] `Organize test status = Success` 확인
- [x] `Latest organize execution` 결과가 전부 `Moved`로 표시됨
- [x] 앱 완전 종료 후 재실행해도 최신 organize 결과가 복원됨
- [x] debug build `Force delete consent path`로 `delete-consent-required` 경로 강제 재현
- [x] `Request Android delete consent (...)` 버튼 노출 확인
- [x] Android system delete consent 팝업 호출 확인
- [x] delete consent `허용` 후 최종 결과가 다시 `8 moved` / `Success`로 정리됨
- [x] delete consent `거절/취소` 후 `0 moved, 8 awaiting delete consent` 상태 유지 확인
- [x] delete consent `거절/취소` 뒤 앱 재시작 후에도 pending 상태와 재요청 버튼이 복구됨
- [x] 앱 재시작 후 Files 상단 안내 / workflow snapshot / latest organize execution card에 delete consent `거절/취소` note 와 재요청 버튼이 재표면화됨

### 기기 페어링 / 전송

- [x] pairing session 생성 및 승인
- [x] Direct HTTP target에서 `Use adb reverse localhost` 적용
- [x] `Check companion health` 성공
- [x] `Queue send to selected device` 실행
- [x] `Transfer test status`에서 `Delivery = Success` 확인
- [x] transfer draft 최종 상태 `Delivered` 확인
- [x] 2026-03-25 build에서 approved transfer task action key가 `files.transfer.execute` 로 일치하도록 정리됨
- [x] 2026-03-25 build에서 shell recovery가 approved transfer task를 durable `transfer_outbox` draft 상태 기준으로 복원하고 organize retry로 오분류하지 않음
- [x] 2026-03-25 build에서 Direct HTTP unresolved payload fallback 이 companion pending transfer inventory + Android pull recovery 로 다시 archive 전송까지 이어짐
- [x] 동일 `transfer_id` 기준으로 manifest-only placeholder 가 archive materialization 으로 승격되고 companion pending 목록에서 제거됨

### 음성 엔트리 / 알림

- [x] `Start voice quick entry` 후 음성 인식 완료
- [x] `Voice transcript ready` 상태 확인
- [x] `Latest transcript` 및 `Recent captures`에 전사 결과 누적 확인
- [x] `Post quick actions notification` 알림 표시 확인
- [x] 알림 탭 시 앱 진입 확인
- [x] quick actions notification `Voice` 버튼으로 voice capture 시작 확인
- [x] quick actions notification `Approvals` 버튼 실행 기록 확인

### foreground recovery

- [x] `Devices > Bridge controls`에서 `Refresh device state` 후 `Shell recovery passed / Manual` 확인
- [x] 앱을 백그라운드로 보냈다가 다시 열었을 때 `Shell recovery passed / Foreground` 확인

### transfer recovery

- [x] `scripts/validate-shell-recovery.ps1` 를 실기기 `adb reverse` Direct HTTP 환경에서 실행
- [x] stale `Sending` draft가 manual shell recovery 후 `Delivered` 로 복구됨
- [x] due retry draft가 manual shell recovery 후 immediate drain 으로 `Delivered` 됨
- [x] delayed retry draft가 recovery 직후 `Queued` 를 유지하고 scheduled retry 뒤 `Delivered` 됨
- [x] `scripts/validate-shell-lifecycle-recovery.ps1` 를 실기기 `adb reverse` Direct HTTP 환경에서 실행
- [x] stale `Sending` draft가 force-stop / relaunch 뒤 `Delivered` 로 복구됨
- [x] due retry draft가 background / foreground resume 뒤에도 `Delivered` 로 정리됨
- [x] delayed retry draft가 force-stop / relaunch 뒤 scheduled retry 시점에 `Delivered` 됨
- [x] `scripts/validate-shell-recovery-soak.ps1` short combined smoke loop 2회가 실기기 `adb reverse` Direct HTTP 환경에서 통과
- [x] foreground due retry smoke가 background-first queue 후 foreground resume 으로 stable 하게 반복됨
- [x] `scripts/validate-shell-recovery-soak.ps1` 30분 duration rerun 이 실기기 `adb reverse` Direct HTTP 환경에서 통과
  - 기록: `19 iterations`, `37 checks`, `0 failures`
  - stale sending manual recovery false-negative를 만들던 debug command auto-open foreground recovery race 제거 후 재검증
- [x] `scripts/validate-shell-recovery.ps1` 를 emulator `emulator_host` Direct HTTP 환경에서도 실행
- [x] `scripts/validate-shell-lifecycle-recovery.ps1` 를 emulator `emulator_host` Direct HTTP 환경에서도 실행
- [x] delayed retry queued 판정이 emulator timing skew 에도 false-negative 없이 유지되도록 validation 스크립트가 안정화됨
- [x] bootstrap-transport-validation 경로가 Android package/activity service readiness wait + install/broadcast/activity retry를 사용해 장시간 soak 중 system-service race를 흡수함
- [x] `scripts/validate-shell-recovery-soak.ps1` 120분 duration rerun 이 emulator `emulator_host` Direct HTTP 환경에서 통과
  - 기록: `34 iterations`, `68 checks`, `0 failures`
- [x] `scripts/validate-direct-http-pull-recovery.ps1` 를 emulator `emulator_host` Direct HTTP 환경에서 실행
- [x] unresolved payload fallback 이 최초 `manifest_only` receipt 후 pending placeholder 로 남고, on-device source materialization 뒤 동일 `transfer_id` 의 `archive_zip` 재전송으로 복구됨
- [x] companion materialized transfer directory 에 실제 `files/` 가 생성되고 placeholder 용 `requested-files/` 는 cleanup 됨
- [x] `scripts/validate-direct-http-drafts.ps1` full rerun 이 emulator `emulator_host` Direct HTTP 환경에서 다시 통과
- [x] `delayed_ack`, `timeout_once` 케이스의 validation false-negative 가 stale sending recovery trigger 와 delayed ack timing 조정 후 사라짐

### companion actions seed

- [x] `scripts/validate-direct-http-companion-actions.ps1` 를 실기기 `adb reverse` Direct HTTP 환경에서 실행
- [x] `session.notify` 가 companion `actions/` 에 materialize되고 desktop notification 이 실제 표시되며 audit `delivered` 가 남음
- [x] `app.open` `record_only` 가 companion `actions/` 에 materialize되고 audit `recorded` 가 남음
- [x] `app.open` `best_effort` 가 `inbox`, `latest_transfer`, `actions_folder`, `latest_action` 을 각각 열고 audit `opened` 가 남음
- [x] `workflow.run` `record_only` 가 `open_latest_transfer`, `open_actions_folder`, `open_latest_action` 셋 다 materialize되고 audit `recorded` 가 남음
- [x] `workflow.run` `best_effort` 가 `open_latest_transfer`, `open_actions_folder`, `open_latest_action` 셋 다 실제 실행하고 audit `completed` 가 남음

---

## Phase 1 완료 기준

- [x] 파일 인덱싱, organize dry-run, approval, execution이 한 흐름으로 이어진다.
- [x] organize 결과가 앱 재시작 후에도 복구된다.
- [x] 실기기에서 desktop companion Direct HTTP 전송이 성공한다.
- [x] 실기기에서 repeatable shell recovery 검증 스크립트가 stale/due/delayed retry를 통과한다.
- [x] voice capture와 transcript 보관이 동작한다.
- [x] quick actions notification의 기본 노출과 app-open 진입이 동작한다.

---

## 남은 보강 검증

- [x] background / 장시간 런타임 기준 transfer retry 및 task recovery soak 검증
  - 실기기 `adb reverse` 기준 short smoke, 8-iteration rerun, 30분 duration rerun 이 이미 통과
  - 2026-03-25 기준 emulator `emulator_host` 120분 duration rerun 도 `34 iterations`, `68 checks`, `0 failures` 로 통과
  - bootstrap/service race와 delayed retry false-negative까지 validation 스크립트에 흡수되어 unattended soak 경로가 닫힘
- [x] Direct HTTP unresolved payload fallback / pull-based recovery 검증
  - 2026-03-25 기준 emulator `emulator_host` 에서 manifest-only pending placeholder -> same `transfer_id` archive recovery -> pending cleanup 까지 자동 검증 통과
---

## Phase 1 판정

현재 기준으로 Phase 1 핵심 경로는 통과다.

- organize 핵심 흐름: 통과
- organize 결과 복구: 통과
- delete consent 허용 경로: 통과
- delete consent 거절/취소 persistence: 통과
- delete consent 거절/취소 note surface: 통과
- Direct HTTP transfer: 통과
- voice quick entry: 통과
- notification 기본 경로: 통과
- notification 액션 버튼: 통과
- foreground recovery 기본 경로: 통과
- shell recovery stale/due/delayed retry: 통과

남아 있는 항목은 실패 복구가 아니라, 이후 Phase 2 범위의 UX 정리와 권한 edge case를 더 다듬는 후속 작업이다.
