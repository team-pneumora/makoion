# Phase 0 체크리스트

Phase 0의 목표: 순수 Dart 패키지로 코어 계약(도메인 모델, 인터페이스, 테스트) 확정.
코드 구현 전에 이 체크리스트를 확인하고, 완료 시 체크한다.

---

## 문서화

- [x] `CLAUDE.md` 생성 (프로젝트 루트)
- [x] `docs/architecture/README.md` 생성 (아키텍처 개요)
- [x] `docs/architecture/decisions.md` 생성 (ADR 8건)
- [x] `docs/architecture/phase0-checklist.md` 생성 (이 파일)
- [ ] 메모리 파일 업데이트

---

## Step 1: 프로젝트 초기화

- [x] 루트 `pubspec.yaml` (Dart workspace)
- [x] `projects/core/pubspec.yaml` (freezed, json_serializable, equatable, uuid)
- [x] `projects/model_router/pubspec.yaml` (http, retry)
- [x] `projects/db/pubspec.yaml` (drift, sqlite3)
- [x] 디렉토리 구조 생성
- [x] `dart pub get` 성공 확인

---

## Step 2: 열거형 + 값 객체

- [x] `projects/core/lib/src/domain/enums.dart`
  - TaskStatus (10개 상태)
  - RiskLevel (low, medium, high)
  - CapabilityStatus (5개)
  - ActionType (5개)
  - ConnectivityMode (3개)
  - DeviceRole (3개)
- [x] `projects/core/lib/src/domain/ids.dart`
  - TaskId, DeviceId, FileNodeId, ConversationId 등

---

## Step 3: 핵심 엔티티

- [x] `projects/core/lib/src/domain/task.dart` (Task, TaskCheckpoint)
- [x] `projects/core/lib/src/domain/file_node.dart` (FileNode, FileSource, SemanticTag)
- [x] `projects/core/lib/src/domain/device.dart` (Device)
- [x] `projects/core/lib/src/domain/conversation.dart` (Conversation, Message)
- [x] `projects/core/lib/src/domain/user_profile.dart` (UserProfile)
- [x] `projects/core/lib/src/domain/memory_item.dart` (MemoryItem)
- [x] `projects/core/lib/src/domain/approval_request.dart` (ApprovalRequest, ApprovalResponse)
- [x] `projects/core/lib/src/domain/audit_event.dart` (AuditEvent)
- [x] `projects/core/lib/src/domain/sync_state.dart` (SyncState, SyncCursor)
- [x] Device pairing protocol / RemoteSession 추가
- [x] FileVersion / FileEmbedding / DeviceCapability 모델 구체화

---

## Step 4: 액션 인텐트 프로토콜

- [x] `projects/core/lib/src/protocol/action_intent.dart` (ActionIntent, ModelOutput)
- [x] `projects/core/lib/src/protocol/file_graph_actions.dart` (FileGraphApi + 요청/응답 타입)

---

## Step 5: 태스크 상태머신

- [x] `projects/core/lib/src/task_engine/task_state_machine.dart`
  - 유효 전이 맵
  - transition() 메서드
  - checkpoint() 메서드
  - recover() 메서드
- [x] `projects/core/lib/src/task_engine/task_transition.dart` (TaskTransitionResult, 가드 조건)

---

## Step 6: 정책/승인/감사 엔진

- [x] `projects/core/lib/src/policy/policy_engine.dart` (PolicyEngine, PolicyDecision, DefaultPolicyEngine)
- [x] `projects/core/lib/src/policy/approval_service.dart` (ApprovalService, InMemoryApprovalService)
- [x] `projects/core/lib/src/policy/audit_logger.dart` (AuditLogger, AuditQuery, InMemoryAuditLogger)
- [x] ApprovalService / AuditLogger 기본 구현

---

## Step 7: 기기 능력 스키마

- [x] `projects/core/lib/src/capability/capability_registry.dart` (CapabilityRegistry, InMemoryCapabilityRegistry)
- [x] `projects/core/lib/src/capability/capabilities.dart` (Capabilities 상수)

---

## Step 8: 메모리 스토어 인터페이스

- [x] `projects/core/lib/src/memory/memory_store.dart` (MemoryStore)

---

## Step 9: AI 모델 라우터

- [x] `projects/model_router/lib/src/provider.dart` (ModelProvider, ModelRequest, ModelResponse)
- [x] `projects/model_router/lib/src/auth_profile.dart` (AuthProfile, ApiCredential)
- [x] `projects/model_router/lib/src/router.dart` (ModelRouter, RoutedModelRouter)
- [x] `projects/model_router/lib/src/failover.dart` (FailoverStrategy)

---

## Step 10: SQLite 스키마

- [x] `projects/db/lib/src/schema.dart` (12개 테이블 DDL)
- [x] `projects/db/lib/src/migrations.dart` (`user_version` 기반 버전 관리)
- [x] `projects/db/lib/src/repositories/` (리포지토리 인터페이스)

---

## Step 11: 테스트

- [x] `projects/core/test/task_engine/task_state_machine_test.dart` — 핵심 전이/가드/복구
- [x] `projects/core/test/policy/policy_engine_test.dart` — 리스크별 시나리오
- [x] `projects/core/test/protocol/action_intent_test.dart` — 직렬화/역직렬화
- [x] `projects/core/test/file_graph/file_graph_api_test.dart` — 10개 API 계약
- [x] `projects/model_router/test/model_router_test.dart` — 라우팅, 페일오버
- [x] `projects/db/test/schema_test.dart` — DDL 실행 + CRUD
- [x] 통합 시나리오: "파일 정리 요청 → 인텐트 → 정책 → 승인 → 실행 → 감사" 전체 흐름

---

## Phase 0 완료 기준

- [x] 모든 도메인 모델이 정의되고 JSON 직렬화/역직렬화 가능
- [x] 태스크 상태머신의 핵심 전이가 테스트됨
- [x] 10개 파일 API 계약이 정의됨
- [x] 정책 엔진이 리스크별로 올바르게 판단함
- [x] 모델 라우터가 다중 프로바이더를 라우팅하고 페일오버함
- [x] SQLite 스키마가 실행되고 CRUD가 동작함
- [x] 패키지별 `dart test` 전체 통과
- [x] `dart analyze` 경고 없음
