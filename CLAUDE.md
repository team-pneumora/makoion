# Makoion (legacy MobileClaw codebase)

## 가장 먼저 볼 문서

- `makoion_dev_instruction_ko.md`
  - 저장소의 **단일 기준 서비스/앱 개발 계획서**
  - 제품 방향이 충돌하면 이 문서를 최우선으로 따른다
  - 현재 구현 이력 문서보다 우선한다

---

## 프로젝트 한줄 정의

**내 폰에서 돌아가는 개인 AI agent 서버 + 내가 연결한 자원을 대신 운용하는 실행 비서**

폰이 agent runtime의 정본(source of truth). 제품 UX는 chat-first. 파일/드라이브/companion/MCP/API는 agent가 활용하는 자원 계층.

---

## 핵심 설계 원칙

1. **Phone is the source of truth** — 대화, 메모리, 작업 상태, 파일 인덱스의 정본은 폰 로컬 DB
2. **Chat-first UX, capability-first architecture** — 사용자는 채팅/음성으로 상호작용하고, 시스템은 capability를 조합해 실행
3. **Tasks, not daemons** — 상시 서버가 아닌 이벤트 기반 작업 엔진. OS가 앱을 죽여도 복구 가능
4. **Capability-driven** — 모델은 OS API를 직접 호출하지 않음. Intent → Policy → Executor → Audit
5. **No hidden privilege** — 위험한 작업은 항상 승인/감사/취소 가능

---

## 현재 Phase: Phase 1 — Android MVP 셸 구현

### 기술 결정 사항
- **Phase 0 언어**: 순수 Dart 패키지 (완료)
- **AI 모델**: 다중 프로바이더 (OpenAI, Claude, Gemini 등)
- **Phase 1 UI 기술**: Android Native (Kotlin + Jetpack Compose)
- **Companion Desktop**: Phase 2 이후
- **DB**: SQLite 단일 DB (NanoClaw 패턴)

### 상태 요약

- **Phase 0**: 완료
- **Phase 1**: `projects/apps/android` 네이티브 셸 + SAF 인덱싱 + 승인/감사 영속화 + 음성 전사 + 파일 그래프 액션 + Android share + organize approval -> MediaStore move 실행 + destination verification + actual delete consent launcher + 기기 페어링 UI + 전송 outbox + background bridge worker + Direct HTTP transport mode + receipt validation/review 상태 + device-level validation fault mode + endpoint presets (`adb reverse` / emulator host) + debug adb transport bootstrap + debug-only cleartext validation config + debug-only FileProvider archive payload generator + manual bridge controls + retry/backoff diagnostics UI + transport audit trace + companion health probe + physical-device adb reverse bootstrap/health probe 검증 + physical-device synthetic manifest-only fault-mode draft validation + physical-device archive payload validation (`archive_zip`, `archive_zip_streaming`) + physical-device archive payload fault-mode validation (`archive_zip`, `archive_zip_streaming`, `partial/malformed/retry/timeout/disconnect/delayed_ack`) + streaming archive 대용량 전송 + unresolved payload manifest-only fallback + retryable transfer backoff/recovery
- **Phase 2 시드**: `projects/apps/desktop-companion` 최소 HTTP endpoint + archive/streaming payload extraction + transfer directory materialization + receipt metadata 응답 완료
- **현재 우선순위**: organize 실행의 on-device 검증/삭제 권한 고도화, background/task recovery 고도화, direct HTTP pull-based recovery 후속 설계/검증

### Phase 0 진행 상태

| Step | 항목 | 상태 |
|------|------|------|
| 0 | 문서화 | 완료 |
| 1 | 프로젝트 초기화 (pubspec.yaml, 패키지 구조) | 완료 |
| 2 | 열거형 + 값 객체 | 완료 |
| 3 | 핵심 엔티티 (Task, FileNode, Device 등) | 완료 |
| 4 | 액션 인텐트 프로토콜 | 완료 |
| 5 | 태스크 상태머신 | 완료 |
| 6 | 정책/승인/감사 엔진 | 완료 |
| 7 | 기기 능력 스키마 | 완료 |
| 8 | 메모리 스토어 인터페이스 | 완료 |
| 9 | AI 모델 라우터 | 완료 |
| 10 | SQLite 스키마 | 완료 |
| 11 | 테스트 | 완료 |

---

## 프로젝트 구조

```
makoion/
├── projects/
│   ├── core/                    # 핵심 도메인 모델 + 인터페이스
│   │   └── lib/src/
│   │       ├── domain/          # 엔티티, 값 객체, 열거형
│   │       ├── task_engine/     # 태스크 상태머신
│   │       ├── file_graph/      # 통합 파일 그래프
│   │       ├── policy/          # 정책/승인/감사
│   │       ├── capability/      # 기기 능력 스키마
│   │       ├── protocol/        # 액션 인텐트, IPC 프로토콜
│   │       └── memory/          # 메모리 스토어 인터페이스
│   │
│   ├── model_router/            # AI 모델 라우터 (다중 프로바이더)
│   │   └── lib/src/
│   │       ├── provider.dart    # 프로바이더 인터페이스
│   │       ├── router.dart      # 라우팅 로직
│   │       ├── auth_profile.dart # 인증 프로필 (OpenClaw 패턴)
│   │       └── failover.dart    # 페일오버 전략
│   │
│   └── db/                      # SQLite 스키마 + 리포지토리
│       └── lib/src/
│           ├── schema.dart      # DDL 정의
│           ├── migrations.dart  # 마이그레이션
│           └── repositories/    # 리포지토리 인터페이스
│   └── apps/
│       ├── android/             # Phase 1 Android native shell
│       │   ├── app/
│       │   ├── gradle/
│       │   └── gradlew.bat
│       └── desktop-companion/   # Phase 2 desktop companion HTTP endpoint seed
│
├── docs/architecture/           # 아키텍처 문서
├── references/                  # 레퍼런스 (openclaw, nanoclaw)
├── makoion_dev_instruction_ko.md     # 마스터 플랜
└── CLAUDE.md                    # 이 파일
```

---

## 코딩 컨벤션

### Dart 스타일
- **네이밍**: `lowerCamelCase` (변수/함수), `UpperCamelCase` (클래스/열거형), `snake_case` (파일)
- **불변 객체**: 모든 도메인 엔티티는 불변(immutable). `freezed` 또는 final 필드 사용
- **타입 안전 ID**: 문자열 ID 대신 `TaskId`, `DeviceId` 등 래퍼 타입 사용
- **barrel export**: 각 패키지는 단일 진입점 (`core.dart`, `model_router.dart`, `db.dart`)

### 패턴
- **리포지토리 패턴**: DB 접근은 리포지토리 인터페이스를 통해서만
- **상태머신**: 태스크 상태 전이는 `TaskStateMachine`을 통해서만
- **Intent 기반**: 모델 출력 → ActionIntent → PolicyEngine → Executor → AuditLogger
- **능력 기반**: 기기 기능은 `CapabilityRegistry`를 통해 조회

### 비목표 / 금지 사항
- OpenClaw Gateway를 폰으로 옮기는 방식 금지
- 모바일 앱 내 범용 shell/exec 금지
- 서버가 없으면 동작 불가한 구조 금지
- 파일을 첨부물 취급하는 설계 금지
- Android 전용 기능을 코어에 하드코딩 금지
- "항상 살아있는 백그라운드 데몬" 전제 금지

---

## 테스트 실행

```bash
# 전체 테스트
cd projects/core && dart test
cd projects/model_router && dart test
cd projects/db && dart test

# 특정 테스트
dart test test/task_engine/task_state_machine_test.dart
```

---

## 레퍼런스

- `references/openclaw/` — 대규모 멀티채널 AI 플랫폼 (7,950 파일)
- `references/nanoclaw/` — 경량 컨테이너 기반 AI 어시스턴트 (272 파일)
- `makoion_dev_instruction_ko.md` — Makoion 마스터 플랜 v1.0
- `docs/architecture/current-status.md` — 현재 구현 범위 + 검증 상태 + 다음 우선순위
- `docs/architecture/decisions.md` — 아키텍처 결정 기록 (ADR)
- `docs/architecture/phase0-checklist.md` — Phase 0 체크리스트
