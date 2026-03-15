# Makoion Agent Runtime Expansion Plan

기준 날짜: 2026-03-15

이 문서는 `makoion_dev_instruction_ko.md`를 보완하는 실행 계획 문서다.
마스터 플랜과 충돌하면 마스터 플랜을 우선한다.

## 1. 이번 계획의 목적

사용자가 정리한 핵심 요구를 현재 Android MVP 셸과 연결해 다음 작업자가 바로 이어받을 수 있는 형태로 재정리한다.

목표는 세 가지다.

1. Makoion을 `폰에서 돌아가는 개인 AI agent 서버`로 더 명확하게 정의한다.
2. 지금 구현된 Android 셸을 어떤 순서로 제품형 agent runtime으로 수렴시킬지 정한다.
3. 각 단계별 작업 전에 반드시 기록해야 하는 기준, 테스트, 산출물을 고정한다.

## 2. 제품 핵심 재정의

사용자 요구와 마스터 플랜을 합치면 Makoion의 핵심은 아래 여덟 가지다.

1. `Mobile-hosted agent server`
   폰이 세션, 작업, 승인, 메모리, 파일 인덱스, 자원 연결 상태의 정본을 가진다.
2. `Resource orchestrator`
   사용자의 자원을 우선순위에 따라 연결하고 agent가 적절한 자원을 고른다.
3. `Chat-only control surface`
   사용자는 음성 또는 텍스트 채팅으로만 지시한다.
4. `Task execution system`
   요청은 항상 task로 바뀌고 계획, 실행, 대기, 재시도, 복구, 완료 보고를 가진다.
5. `Model-portable assistant`
   OpenAI, Claude, Gemini 등 다중 모델/다중 프로바이더를 지원한다.
6. `Simple product UI`
   메인 화면은 채팅 중심이고, 설정과 운영 도구는 분리한다.
7. `Durable audit and history`
   진행 과정, 승인, 실패, 재시도, 결과물이 모두 로컬에 남아야 한다.
8. `Expandable automation platform`
   뉴스 수집, 브라우저 조사, 파일 관리, 개인 비서, 코드 생성, 자동화 생성 같은 일을 결국 수행할 수 있어야 한다.

## 3. 자원 우선순위

Makoion은 아래 순서로 자원을 확장한다.

### Priority 1

- 폰 로컬 저장소
- MediaStore
- SAF document tree
- 폰의 센서/알림/음성/앱 연동

### Priority 2

- Google Drive
- OneDrive
- Dropbox
- 기타 클라우드 문서 저장소

### Priority 3

- 외부 접근 가능한 PC/Mac companion
- 이후 태블릿/다른 모바일 companion

### Priority 4

- MCP 서버
- 외부 API 프로필
- 브라우저 자동화
- 사용자 정의 workflow/automation

## 4. 사용자에게 약속할 대표 시나리오

이 계획은 아래 시나리오를 끝까지 수행할 수 있는 제품을 목표로 한다.

- “매일 아침 AI 관련 뉴스를 수집해서 요약하고 텔레그램으로 보내줘.”
- “브라우저로 자료를 찾아서 핵심만 정리하고 관련 링크를 저장해줘.”
- “특정 폴더의 이미지, 문서, 텍스트 파일을 규칙에 따라 정리해줘.”
- “내 일정, 파일, 메시지, 외부 API를 연결한 AI 비서처럼 동작해줘.”
- “자동화 하나 만들어서 주기적으로 돌리고, 실패하면 알려줘.”
- “코드나 모바일 앱 초안을 만들고, 결과물을 저장하고 공유 준비까지 해줘.”

## 5. OpenClaw / NanoClaw에서 가져올 것

### OpenClaw에서 흡수할 것

- 세션 중심 agent runtime
- 모델 라우팅과 auth-profile/failover
- 브라우저, MCP, 채널, automation을 capability로 다루는 방식
- companion/node 개념
- local-first control plane 철학

### NanoClaw에서 흡수할 것

- 단일 SQLite 정본
- 작은 단위의 작업 스케줄링과 복구
- 자원 격리와 명시적 허용 범위 사고방식
- 복잡한 설정 대신 코드와 문서 중심의 명료한 구조

### 둘 다 그대로 가져오지 않을 것

- OpenClaw의 gateway-first 구조
- NanoClaw의 데스크톱/컨테이너 전제 런타임
- 모바일에서 유지 불가능한 상시 daemon 전제
- 모바일 앱 안의 범용 shell/exec

## 6. 목표 제품 구조

```text
[User]
  -> [Chat / Voice UI on Phone]
  -> [Phone Agent Runtime]
       -> [Session Manager]
       -> [Turn Processor]
       -> [Planner]
       -> [Task Engine]
       -> [Resource Registry]
       -> [Capability Broker]
       -> [Policy / Approval / Audit]
       -> [Memory Store]
       -> [Model Router]
       -> [Automation Scheduler]
       -> [Recovery Coordinator]
  -> [Connected Resources]
       -> [Phone Local Storage]
       -> [Cloud Drives]
       -> [Companion PCs]
       -> [MCP Servers]
       -> [External APIs]
       -> [Browser Capability]
```

## 7. 단계별 개발 트랙

## Track A. Product Shell Stabilization

목표:
- 현재 Android 셸을 완전히 chat-first 제품 구조로 고정한다.

주요 작업:
- Settings 안에 남아 있는 디버그/운영 도구와 제품 설정을 더 명확히 분리
- 자원 우선순위와 연결 상태를 Settings에서 명시적으로 표면화
- Chat에서 가능한 capability와 불가능한 capability를 명확히 안내

검증:
- `Chat / Dashboard / History / Settings`만으로 주요 동선 설명 가능
- 파일/기기 콘솔이 메인 동선이 되지 않음

## Track B. Turn Processor / Planner Hardening

목표:
- 현재 키워드 기반 turn router를 더 명시적인 planner contract로 올린다.

주요 작업:
- `Answer / Question / Plan / ActionIntent / Escalation` 계약을 Android runtime에 반영
- turn context에 session, thread, task, selected resource, model preference 포함
- planner output을 audit와 history에 구조적으로 저장

검증:
- 하나의 채팅 turn이 계획과 실행 결과를 구조적으로 남김

## Track C. Resource Registry

목표:
- 로컬 저장소, 클라우드, companion, MCP, API를 같은 연결 모델로 수렴시킨다.

주요 작업:
- `ResourceConnection`, `ResourceCapability`, `ResourceHealthState`, `ResourcePolicy` 정의
- Android shell에서 자원 상태를 Settings에 표시
- 우선순위 1~4 자원을 같은 UI/DB/도메인 모델로 관리

검증:
- 사용자가 어떤 자원이 연결되었고 어떤 기능이 가능한지 한 화면에서 이해 가능

## Track D. Capability Execution Expansion

목표:
- organize/transfer 외에도 research/browser/automation 계열 capability를 추가한다.

주요 작업:
- 브라우저 조사 capability 설계
- 뉴스 수집/정리/전달 workflow 설계
- 코드 생성/문서 생성/자동화 생성 task 유형 정의
- 결과물 저장과 보고 형식 정의

검증:
- 대표 시나리오 3개 이상이 chat만으로 완료

## Track E. Provider & Settings

목표:
- 다중 모델과 API 설정을 제품 기능으로 올린다.

주요 작업:
- OpenAI/Claude/Gemini provider profile 저장
- 모델 선택 정책: 기본, 작업별, 자원별 override
- API 키/토큰 보관을 Android 보안 저장소와 연결

검증:
- Settings에서 provider 추가/비활성화/기본 선택 가능

## Track F. Reliability, Recovery, Automation

목표:
- 장시간 background/task recovery와 스케줄 작업을 제품 수준으로 만든다.

주요 작업:
- task checkpoint 세분화
- scheduled automation task 추가
- background recovery soak 테스트
- 실패 보고와 retry 정책 통일

검증:
- 앱 종료/재시작/foreground resume 후 in-flight task 복구
- schedule 기반 task가 기록과 함께 실행

## 8. 즉시 실행할 단계

이번 계획 수립 이후에는 아래 순서로 진행한다.

| Step | 작업 | 사용자 경험 강화 | 영향 자원/능력 | UI 구분 | 검증 | 관련 기준 |
|------|------|------------------|----------------|--------|------|-----------|
| 0 | 계획 문서/진행 기록 정리 | 다음 작업자가 방향을 잃지 않음 | 전체 | 제품/개발 공통 | 문서 리뷰 | 마스터 플랜 15 |
| 1 | Settings에 resource stack 표면 추가 | 사용자가 연결 우선순위를 즉시 이해 | local, cloud, companion, API/MCP | 제품 UI | Android compile | 5.4, 6.2, 8 |
| 2 | planner contract 정리 및 turn 결과 구조화 | 채팅 요청이 더 예측 가능해짐 | task, audit | 제품 내부 | unit + compile | 7, 12.4 |
| 3 | provider/settings 데이터 모델 추가 | 다중 모델 선택의 기반 | model provider | Settings | unit + compile | 14.3 |
| 4 | Resource Registry 저장 구조 도입 | 연결 자원 확장 가능 | resources | 내부/Settings | unit + migration test | 7.2, 12.3 |
| 5 | cloud drive connector skeleton | Priority 2 착수 | gdrive/onedrive | Settings | mock/integration | 8.1 |
| 6 | browser research task skeleton | 정보 수집 capability 시작 | browser | chat task | compile + task tests | 4, 11 Track 4 |
| 7 | scheduled automation task skeleton | 뉴스 수집/알림 같은 흐름 시작 | scheduler, notification | Dashboard/History | worker tests | 11 Track 6 |
| 8 | provider credential vault skeleton | 실제 모델 사용 준비 시작 | provider credential, secure settings | Settings | unit + compile | 14.3, Track E |
| 9 | MCP/API registry skeleton | Priority 4 외부 연동의 표준화 시작 | MCP, external API | Settings | unit + compile | 8.4, Track C/E |
| 10 | delivery channel registry skeleton | automation 결과 전달 기반 시작 | notification, telegram, email | Settings/Dashboard | unit + compile | 4, Track D/F |
| 11 | code generation task skeleton | 코드/앱 생성 시나리오의 첫 작업선 추가 | codegen, workspace draft | chat task | compile + task tests | 4, Track D |

## 9. 작업 수주 템플릿

이후 모든 구현 단계는 아래 형식으로 기록한다.

1. 어떤 사용자 요청 경험을 강화하는가
2. 어느 Track에 속하는가
3. 어떤 자원 또는 capability에 영향을 주는가
4. 제품 UI인가, 디버그 UI인가
5. 검증 방법은 무엇인가
6. 마스터 플랜의 어떤 섹션과 연결되는가

## 10. 테스트 및 기록 원칙

- 각 단계 완료 후 가능한 가장 가까운 단위의 테스트를 실행한다.
- Android UI/셸 변경은 최소 `:app:compileDebugKotlin`을 기본 검증으로 삼는다.
- Dart core/db/model_router 변경은 해당 패키지 테스트를 실행한다.
- 결과는 `docs/plans/agent-runtime-expansion-progress-20260315.md`에 남긴다.
- 실패한 테스트와 원인, 우회 여부도 기록한다.

## 11. Git 운영 원칙

- 단계 완료 후 테스트가 통과하면 커밋한다.
- 커밋 후 `git push`를 시도하고 결과를 진행 기록에 남긴다.
- 푸시 실패 시 원인과 다음 조치도 기록한다.

## 12. 다음 연속 착수 범위

Step 7 이후에는 아래 순서로 이어간다.

1. provider credential을 단순 metadata가 아니라 폰 내부 vault abstraction으로 저장하고 Settings에서 입력/갱신 경로를 만든다.
2. MCP/API endpoint를 resource registry와 같은 연결 모델로 seed해 Priority 4 자원을 제품 UI에서 드러낸다.
3. automation이 notification/telegram/email 같은 delivery channel과 연결될 수 있도록 channel registry placeholder를 만든다.
4. 대표 시나리오 중 `코드 만들기` 요청을 별도 task skeleton으로 분리해 browser/automation 다음 capability line을 연다.
