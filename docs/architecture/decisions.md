# 아키텍처 결정 기록 (ADR)

## ADR-001: Phase 0는 순수 Dart 패키지 (UI 없음)

**상태**: 확정
**날짜**: 2026-03-08

**맥락**: MobileClaw의 핵심 로직을 먼저 정의해야 한다. UI 기술(Flutter vs Native)은 아직 미정.

**결정**: Phase 0는 순수 Dart 패키지로 도메인 모델, 인터페이스, 테스트만 정의한다. Flutter나 네이티브 프레임워크에 의존하지 않는다.

**근거**:
- UI 기술 결정을 Phase 1로 미룰 수 있다
- 어떤 UI 프레임워크를 선택하든 코어 로직을 그대로 사용 가능
- MobileClaw은 파일/OS 통합이 핵심인데, Flutter가 적합한지 추가 검토 필요
- 코어 계약을 먼저 확정해야 구현이 흔들리지 않음

**대안 검토**:
- Flutter 프로젝트로 시작: Phase 0에서는 UI가 불필요하므로 과잉
- KMP: Kotlin 경험이 적어서 보류
- Rust: FFI 복잡도가 높아서 보류

---

## ADR-002: Phase 1 UI 셸은 Android Native (Kotlin + Compose)

**상태**: 확정
**날짜**: 2026-03-09

**맥락**: MobileClaw은 파일 인덱싱, 백그라운드 스캔, 음성, 알림, 보안 저장소, 기기 페어링 등 OS 깊은 통합이 핵심이다. 이미 Phase 0에서 Dart 코어 계약은 완성됐고, Phase 1은 Android MVP를 가장 빠르게 제품 형태로 올려야 한다.

**결정**: Phase 1은 `projects/apps/android`에 Kotlin + Jetpack Compose 네이티브 셸로 구현한다. 현재 `projects/core`, `projects/db`, `projects/model_router`는 계약/참조 구현으로 유지하고, Android 셸에서 필요한 기능을 네이티브로 붙인다.

**근거**:
- 비전 문서의 권장 구조가 Android shell `Kotlin + Jetpack Compose`다
- 파일/음성/권한/백그라운드 작업의 대부분이 네이티브 통합 영역이다
- Flutter를 택해도 어려운 문제는 그대로 남고 Platform Channel 표면만 커질 가능성이 높다
- Android MVP를 제품 품질로 가져가기 위한 구조적 리스크가 가장 낮다

**보류 사항**:
- iOS 셸은 Phase 3에서 SwiftUI 기준으로 추가
- 장기 공통 코어는 Dart 유지 vs KMP/Rust 전환을 Phase 1 후반에 다시 평가

---

## ADR-003: AI는 다중 프로바이더 (프로바이더 불가지론)

**상태**: 확정
**날짜**: 2026-03-08

**맥락**: 단일 프로바이더에 종속되면 가격/가용성/성능 변동에 취약.

**결정**: ModelRouter를 통해 OpenAI, Anthropic, Google Gemini 등 다중 프로바이더를 지원한다. OpenClaw의 auth-profile 패턴을 적용하여 키 로테이션, 페일오버를 구현한다.

**근거**:
- 프로바이더 장애 시 자동 전환 가능
- 작업 유형별 최적 모델 선택 가능 (비용/성능 트레이드오프)
- API 키 로테이션으로 rate limit 대응
- OpenClaw에서 검증된 패턴

---

## ADR-004: SQLite 단일 DB (NanoClaw 패턴)

**상태**: 확정
**날짜**: 2026-03-08

**맥락**: 모바일에서 상태 관리가 필요. 서버 DB가 아닌 로컬 DB가 정본.

**결정**: 모든 상태(대화, 작업, 파일 인덱스, 기기, 승인, 감사)를 단일 SQLite DB에 저장한다.

**근거**:
- NanoClaw에서 검증된 패턴 (`messages.db` 하나로 모든 상태)
- Android/iOS 모두 SQLite 네이티브 지원
- 트랜잭션 보장, 복구 용이
- "폰이 정본" 원칙에 부합
- 서버 없이 동작 가능

**스키마**: 12개 테이블 (conversations, messages, tasks, task_checkpoints, devices, file_nodes, file_tags, file_embeddings, approval_requests, audit_events, memory_items, sync_state)

---

## ADR-005: 액션 인텐트 기반 실행 모델

**상태**: 확정
**날짜**: 2026-03-08

**맥락**: AI 모델이 OS API를 직접 호출하면 보안 위험. 비전 문서의 "No hidden privilege" 원칙.

**결정**: 모델은 ActionIntent만 생성할 수 있다. 실행은 항상 Intent → Policy → Executor → Audit 경로를 거친다.

**모델 출력 유형**: Answer, Question, Draft, ActionIntent, Escalation

**실행 흐름**:
```
모델 → ActionIntent 제안 → PolicyEngine 검증 → 필요 시 사용자 승인 → Executor 실행 → AuditLogger 기록
```

**근거**:
- 위험한 작업의 자동 실행 방지
- 모든 액션에 감사 추적 가능
- dry-run 지원 (먼저 계획을 보여주고 승인 후 실행)
- 비전 문서 Section 7 "작업 실행 모델" 준수

---

## ADR-006: 파일을 1급 객체로 취급

**상태**: 확정
**날짜**: 2026-03-08

**맥락**: MobileClaw는 "채팅 앱"이 아니라 "파일 중심 개인 작업 허브". 비전 문서의 "File-first, not chat-first" 원칙.

**결정**: Unified File Graph로 모든 파일 소스(로컬, 클라우드, Companion)를 하나의 그래프로 추상화한다. 10개 핵심 파일 API를 제공한다.

**10개 API**: search, preview, summarize, organize, move, copy, share, dedupe, send_to_device, request_from_device

**근거**:
- 사용자가 "파일이 어디 있든" 동일하게 다룰 수 있음
- 검색/요약/정리/중복제거가 그래프 위에서 동작
- 비전 문서 Section 8 "파일 중심 아키텍처" 준수

---

## ADR-007: OpenClaw auth-profile 패턴으로 프로바이더 페일오버

**상태**: 확정
**날짜**: 2026-03-08

**맥락**: 다중 AI 프로바이더를 사용할 때 키 관리, 장애 대응, rate limit 처리가 필요.

**결정**: OpenClaw의 auth-profile 시스템을 참고하여:
- 프로바이더별 복수 API 키 지원
- 키 로테이션 (순환 사용)
- 장애 시 쿨다운 + 다른 프로바이더로 자동 전환
- 프로바이더별 모델 목록 관리

**참고 파일**: `references/openclaw/src/agents/auth-profiles/`

---

## ADR-008: 비전 문서 비목표 준수

**상태**: 확정
**날짜**: 2026-03-08

**맥락**: 비전 문서(`mobileclaw_dev_instruction_ko.md`)의 Section 3.2, 19에 명시된 비목표.

**절대 하지 말 것**:
1. OpenClaw Gateway를 폰으로 옮기는 방식 ← 서버 중심 아닌 폰 중심
2. 모바일 앱 내 범용 shell/exec ← 보안 위험
3. 서버 없으면 동작 불가한 구조 ← 서버는 보조 계층일 뿐
4. 파일을 첨부물 취급 ← 파일이 1급 객체
5. Android 전용 기능을 코어에 하드코딩 ← capability flag 사용
6. 항상 살아있는 백그라운드 데몬 전제 ← 이벤트 기반
7. 원격 기기를 중앙 서버처럼 취급 ← Companion Node일 뿐
