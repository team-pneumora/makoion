# Makoion 아키텍처

## 문서 인덱스

- `current-status.md` — 현재 구현 범위, 검증 상태, 다음 우선순위
- `decisions.md` — ADR
- `phase0-checklist.md` — Phase 0 체크리스트
- `phase1-checklist.md` — Phase 1 실기기 체크리스트 및 검증 기록
- `../plans/agent-runtime-expansion-plan-20260315.md` — 사용자 핵심 요구를 반영한 상세 실행 계획
- `../plans/agent-runtime-expansion-progress-20260315.md` — 단계별 진행 기록

---

## 시스템 아키텍처

```
[User]
  → [Chat / Voice / Widget / Share Sheet / Notification Actions]
  → [Phone Agent Runtime]
       → [Session Manager]      세션 관리 (대화, 작업)
       → [Turn Processor]       입력 처리, 계획/질문/실행 분기
       → [Planner]              자연어 요청 -> 실행 가능한 intent/task 분해
       → [Task Engine]          이벤트 기반 작업 엔진
       → [Resource Registry]    연결 자원/기기/capability 정본
       → [Unified File Graph]   파일/폴더/컬렉션 자원 계층
       → [Capability Broker]    실제 실행 가능한 도구 선택/호출
       → [Policy / Approval / Audit]
       → [Memory Store]         영구 메모리 + 벡터 검색
       → [Model Router]         다중 AI 프로바이더 라우팅
       → [Recovery Coordinator] 재시작/foreground 복구
       → [Companion Router]     원격 기기 제어
  → [Cloud Providers / LLM APIs / Push Services]
  → [Companion Nodes: PC / Mac / iPad / Tablet]
```

---

## 패키지 관계도 (Phase 0)

```
┌─────────────────┐
│   model_router   │  AI 모델 라우팅, 페일오버
│   (Dart 패키지)   │
└────────┬────────┘
         │ depends on
         ↓
┌─────────────────┐     ┌─────────────────┐
│      core        │←────│       db         │
│   (Dart 패키지)   │     │   (Dart 패키지)   │
│                  │     │                  │
│ - 도메인 모델     │     │ - SQLite 스키마   │
│ - 태스크 상태머신 │     │ - 마이그레이션     │
│ - 파일 그래프 API │     │ - 리포지토리      │
│ - 정책/승인/감사  │     │                  │
│ - 기기 능력 스키마│     │                  │
│ - 메모리 인터페이스│     │                  │
└─────────────────┘     └─────────────────┘
```

---

## 핵심 도메인 모델

### 엔티티 관계

```
UserProfile ──1:N──→ Conversation ──1:N──→ Message
                                              │
                                     ModelOutput (Answer/Question/Draft/ActionIntent/Escalation)
                                              │
                                        ActionIntent
                                              │
                               ┌──────────────┼──────────────┐
                               ↓              ↓              ↓
                          PolicyDecision  ApprovalRequest  AuditEvent
                                              │
                                              ↓
                                            Task ──1:N──→ TaskCheckpoint
                                              │
                                        ┌─────┴─────┐
                                        ↓           ↓
                                   FileNode      Device
                                      │             │
                                 ┌────┴────┐   DeviceCapability
                                 ↓         ↓
                            SemanticTag  FilePreview
                                         FileEmbedding
                                         FileVersion
```

### 주요 엔티티 설명

| 엔티티 | 역할 |
|--------|------|
| `Task` | 작업 단위. 상태머신으로 생명주기 관리 |
| `TaskCheckpoint` | 작업 중간 저장점. 앱 종료 후 복구용 |
| `ActionIntent` | AI 모델이 생성하는 실행 의도. OS API 직접 호출 금지의 핵심 |
| `FileNode` | 통합 파일 그래프의 노드. 로컬/클라우드/Companion 구분 없이 동일 |
| `FileSource` | 파일의 원본 위치 (local, gdrive, onedrive, dropbox, companion) |
| `SemanticTag` | 파일의 의미적 태그 (날짜, 위치, 프로젝트, 인물, 문서유형) |
| `Device` | 연결된 기기 (Phone Agent Runtime, Companion Desktop/Tablet) |
| `ApprovalRequest` | 고위험 작업의 사용자 승인 요청 |
| `AuditEvent` | 모든 액션의 감사 기록 |
| `MemoryItem` | 영구 기억 항목 + 벡터 임베딩 |

---

## 태스크 상태머신

### 상태 전이표

```
queued ──→ ready ──→ running ──→ succeeded (terminal)
  │          │          │──→ failed (terminal)
  │          │          │──→ cancelled (terminal)
  │          │          │──→ waitingUser ──→ running / cancelled
  │          │          │──→ waitingDevice ──→ running / failed / cancelled
  │          │          │──→ waitingNetwork ──→ running / failed / cancelled
  │          │          └──→ delegated ──→ running / succeeded / failed
  │          └──→ cancelled (terminal)
  └──→ cancelled (terminal)
```

### 터미널 상태
- `succeeded` — 작업 성공 완료
- `failed` — 작업 실패 (체크포인트에서 복구 가능)
- `cancelled` — 사용자에 의해 취소

### 체크포인트 & 복구
- 각 작업은 중간 단계마다 `TaskCheckpoint`를 저장
- 앱이 종료되어도 체크포인트에서 작업 재개 가능
- 실패한 작업은 마지막 체크포인트에서 재시도 가능

---

## 파일 그래프

### 개념

모든 파일 소스를 하나의 논리적 그래프로 통합:

```
Unified File Graph
├── Source: 폰 사진 보관함
│   ├── Folder: 2024-03
│   │   ├── Asset: IMG_001.jpg [tags: 날짜=3월, 위치=서울]
│   │   └── Asset: IMG_002.jpg
│   └── Folder: 2024-04
├── Source: Google Drive
│   ├── Folder: 세금 문서
│   │   └── Document: 2023_세금신고.pdf
│   └── Folder: 프로젝트
├── Source: PC Companion (192.168.1.10)
│   └── Folder: 발표자료
│       └── Document: keynote_v3.pptx
└── Source: OneDrive
    └── Folder: 공유 문서
```

### File Graph API (10개 핵심 연산)

| API | 설명 | 리스크 |
|-----|------|--------|
| `files.search` | 파일 검색 (이름, 태그, 내용) | Low |
| `files.preview` | 파일 미리보기 생성 | Low |
| `files.summarize` | 파일 내용 요약 | Low |
| `files.organize` | 파일 정리 계획 생성 (dry-run) | Medium |
| `files.move` | 파일 이동 | Medium~High |
| `files.copy` | 파일 복사 | Medium |
| `files.share` | 파일 공유 | High |
| `files.dedupe` | 중복 파일 탐지 및 제거 | High |
| `files.send_to_device` | 다른 기기로 파일 전송 | Medium |
| `files.request_from_device` | 다른 기기에서 파일 가져오기 | Medium |

---

## 정책/승인/감사 흐름

```
1. AI 모델이 ActionIntent 생성
   ↓
2. PolicyEngine.evaluate(intent, device)
   → PolicyDecision { allowed, requiresApproval, assessedRisk }
   ↓
3. requiresApproval == true?
   → ApprovalService.requestApproval(intent, decision)
   → 사용자에게 승인 UI 표시
   → 사용자 승인/거부
   ↓
4. Executor가 로컬 브로커 또는 Companion Node 호출
   ↓
5. AuditLogger.log(event)
   → 누가 요청, 어떤 intent, 어떤 capability, 결과, 되돌릴 수 있는지
   ↓
6. 실패 시 TaskCheckpoint 기반 재시도
```

### 리스크 등급

| 등급 | 예시 | 승인 필요 |
|------|------|-----------|
| Low | 검색, 요약, 초안 생성 | 불필요 |
| Medium | 일정 생성, 파일 이동 제안, 폴더 분류 | 설정에 따라 |
| High | 삭제, 대량 변경, 외부 전송, 원격 제어 | **필수** |

---

## 기기 능력 스키마

### 능력 카테고리

| 카테고리 | 능력 | 기기 |
|----------|------|------|
| 공통 | files.list, files.read_metadata, files.transfer, app.open, session.ping | 모두 |
| 데스크톱 | window.focus, workflow.run, screen.stream, clipboard.read_write | PC/Mac |
| 태블릿 | canvas.open, handoff.start, preview.display | iPad/Tablet |
| 모바일 | camera, calendar, contacts, location, voice, notifications | Phone |

### Capability Discovery
1. 기기 페어링 시 능력 목록 교환
2. 런타임에 능력 상태 변경 가능 (supported → denied 등)
3. 능력이 없는 작업은 PolicyEngine이 차단

---

## 레퍼런스 프로젝트 요약

### OpenClaw에서 가져온 패턴
- Auth Profile 시스템: API 키 로테이션, OAuth 폴백, 쿨다운
- 프로바이더 페일오버: 장애 시 자동 전환
- 플러그인 SDK: 확장 가능한 모듈 구조 (Phase 2)

### NanoClaw에서 가져온 패턴
- SQLite 단일 DB: 모든 상태를 한 곳에
- 태스크 스케줄러: cron/interval/once 패턴
- 파일 기반 IPC: Companion 통신에 활용 가능
- 마운트 보안: 파일 접근 허용목록
