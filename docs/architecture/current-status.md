# 현재 진행 상태 및 로드맵

기준 날짜: 2026-03-10

이 문서는 MobileClaw의 현재 구현 범위, 검증 상태, 남은 우선순위를 한 곳에서 빠르게 확인하기 위한 상태 문서다.

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
  - Overview / Files / Approvals / Devices 화면
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
  - large or unknown-size payload -> streaming archive upload
  - unresolved payload -> manifest-only fallback
  - stale `Sending` draft recovery
  - retryable direct HTTP/network failure에 대한 per-draft backoff + delayed retry scheduling

### Phase 2 Seed Desktop Companion

- `projects/apps/desktop-companion` 최소 companion endpoint 완료
  - `GET /health`
  - `POST /api/v1/transfers`
  - manifest-only 요청 materialization
  - zip / streaming archive payload extraction
  - receipt metadata 응답

---

## 2. 현재 확인된 동작

### 로컬 검증 완료

- `projects/core` / `projects/db` / `projects/model_router`
  - analyze/test 통과
- `projects/apps/android`
  - `:app:compileDebugKotlin`
  - `:app:assembleDebug`
- `projects/apps/desktop-companion`
  - `javac` 컴파일
  - `/health` 응답 확인
  - manifest POST 확인
  - zip POST 확인
  - debug receipt mode (`partial_receipt`, `malformed_receipt`, `retry_once`) 응답 확인
  - `scripts/validate-fault-modes.ps1` 로 `timeout_once`, `disconnect_once` 1회 실패 후 재시도 성공 확인
  - `scripts/prepare-adb-reverse.ps1` 로 실기기 USB `adb reverse` 준비 가능

### 아직 부족한 검증

- Android organize 실행 경로의 실제 on-device 검증
- MediaStore delete consent 런처의 실기기 검증
- direct HTTP timeout / malformed receipt / retry / disconnect 시나리오의 실기기 검증
- 대용량 streaming archive payload의 실기기 검증

---

## 3. 현재 제품 상태를 한 줄로 요약하면

“폰 중심 파일 허브의 Android MVP shell은 이미 동작하고 있고, desktop companion seed까지 붙었지만, organize 실행의 권한/검증 고도화와 전송 검증/복구 고도화가 남아 있는 상태”다.

---

## 4. 다음 우선순위

organize 실행의 on-device 검증 및 삭제 권한 고도화

- 실제 기기/에뮬레이터에서 move/copy/delete 결과 확인
- source delete 불가 케이스에 대한 UI/감사 표시 개선
- delete-consent-required 결과의 Android consent launcher 실기기 확인
- SAF / MediaStore 별 결과 차이 정리

### 우선순위 2

direct HTTP transport 검증 고도화

- retry scheduled draft가 companion 복구 이후 정상 drain 되는지 확인
- partial receipt / malformed receipt / timeout 케이스의 실기기 검증
- delayed_ack + retry/backoff 타이밍의 실기기 검증 및 캡처

### 우선순위 3

background refresh / task recovery 고도화

- 앱 재시작 후 in-flight task 복원 강화
- periodic refresh 또는 event-triggered refresh 개선
- approval / transfer / organize 실행 이력 복구 정리

### 우선순위 4

streaming archive 이후의 후속 대용량 전략

- 네트워크가 불안정한 환경에서의 pull-based companion recovery 검토
- 아주 큰 배치에서 파일별 분할/재개 단위가 필요한지 검토
- manifest-only를 유지해야 하는 unresolved source 케이스 축소

---

## 5. 다음 구현 때 참고할 진입점

- Android shell 상태/흐름: `projects/apps/android/app/src/main/java/io/makoion/mobileclaw/ui/`
- Android data 계층: `projects/apps/android/app/src/main/java/io/makoion/mobileclaw/data/`
- Desktop companion seed: `projects/apps/desktop-companion/src/io/makoion/desktopcompanion/Main.java`
- 프로젝트 전체 요약: `CLAUDE.md`
- 기술 결정: `docs/architecture/decisions.md`
