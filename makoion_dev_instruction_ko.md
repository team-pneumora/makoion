# Makoion 서비스 및 앱 개발 마스터 플랜 v1.0

기준 날짜: 2026-03-13

---

## 0. 문서 목적

이 문서는 **Makoion의 단일 기준 개발 계획서**다.

목표는 다음 3가지다.

1. 누가 저장소를 이어받아도 제품의 본질을 동일하게 이해하게 한다.
2. 어떤 기능을 먼저 만들고 무엇을 뒤로 미뤄야 하는지 판단 기준을 제공한다.
3. 현재 저장소의 구현을 앞으로 어떤 방향으로 수렴시켜야 하는지 명확히 한다.

이 문서는 단순 비전 문서가 아니다.
이 문서는 **제품 정의 + 서비스 구조 + 앱 구조 + 실행 계획 + 작업 우선순위 규칙**을 모두 포함하는 마스터 플랜이다.

문서 우선순위:

- 제품 방향과 우선순위가 충돌하면 **이 문서를 최우선으로 따른다.**
- 기존 `docs/architecture/*` 문서 중 일부는 구현 이력과 중간 상태 기록으로 읽는다.
- 기존 문서의 `file-first` 표현과 이 문서의 `chat-first AI agent` 표현이 충돌하면 **이 문서가 맞다.**

---

## 1. 제품 한 줄 정의

**Makoion = 내 폰에서 돌아가는 개인 AI Agent 서버 + 내가 연결한 모든 자원을 대신 운용하는 실행 비서**

핵심은 다음이다.

- 폰은 단순 클라이언트가 아니라 **AI agent의 주 실행 서버**다.
- 사용자가 연결한 폴더, 드라이브, PC, 다른 기기, MCP, 외부 API, 로컬 기능이 모두 **에이전트의 사용 가능한 자원**이 된다.
- 사용자는 Makoion과 **채팅/음성 대화**로만 상호작용한다.
- Makoion은 자신에게 할당된 자원을 조합해 사용자의 요청을 **완결된 결과**까지 수행해야 한다.

---

## 2. 제품 본질

Makoion은 다음 4가지를 합친 제품이다.

1. **Phone-hosted Agent Runtime**
2. **Resource Orchestrator**
3. **Task Execution System**
4. **Chat-first Personal AI Interface**

즉, Makoion의 중심은 파일 브라우저도 아니고 원격 제어 앱도 아니다.
그 중심은 **내 폰에 상주하는 개인 AI agent 실행 환경**이다.

파일 관리, 드라이브 연결, PC companion, MCP, 외부 API는 모두 제품의 본체가 아니라
**agent가 사용하는 capability layer**다.

---

## 3. 반드시 지켜야 할 제품 해석

### 3.1 Makoion은 무엇인가

- 내 폰에서 동작하는 개인 AI agent
- 사용자의 자원 연결 상태를 이해하고 활용하는 orchestrator
- 요청을 받고 계획하고 실행하고 복구하고 보고하는 작업 시스템
- 채팅과 음성으로 모든 기능에 접근하는 모바일 앱

### 3.2 Makoion은 무엇이 아닌가

- 단순 파일 정리 앱
- 단순 채팅 UI
- OpenClaw Gateway를 모바일로 그대로 옮긴 앱
- 모바일 내부에 범용 shell/exec를 넣은 자동화 앱
- 중앙 서버 없이는 쓸 수 없는 SaaS 전제 앱

### 3.3 가장 중요한 판단 문장

어떤 기능이 다음 질문에 `예`라고 답하지 못하면 우선순위를 낮춘다.

1. 이 기능은 Makoion을 더 **폰-호스팅 AI agent 서버**답게 만드는가?
2. 이 기능은 사용자가 **대화로 요청하고 결과를 받는 경험**을 더 직접적으로 강화하는가?
3. 이 기능은 agent가 활용할 수 있는 **자원 연결 또는 실행 능력**을 늘리는가?
4. 이 기능은 작업을 더 **안전하고 복구 가능하게** 만드는가?

---

## 4. 사용자에게 약속하는 핵심 경험

사용자는 Makoion에게 이렇게 말할 수 있어야 한다.

- “구글 드라이브랑 폰 문서함, 데스크탑 다운로드 폴더까지 다 찾아서 세금 관련 파일만 모아줘.”
- “노트북에 있는 발표자료 최신본 열고, 필요한 PDF는 폰으로 보내줘.”
- “이번 주 받은 계약서들을 프로젝트별로 정리하고 요약까지 남겨줘.”
- “내가 연결한 MCP랑 API 써서 이 자료를 분석하고 필요한 결과물까지 만들어줘.”
- “작업 끝나면 알림만 줘. 중간에 내가 승인해야 하면 물어봐.”

사용자의 기대는 단순 응답이 아니다.

- 답변
- 실행 계획
- 실제 작업 수행
- 진행 상태 보고
- 실패 시 재시도 또는 대안 제시
- 완료 결과 전달

이 전부가 Makoion의 책임 범위다.

---

## 5. 핵심 설계 원칙

### 5.1 Phone is the agent server

- 폰은 UI 앞단이 아니라 **실행 주체**다.
- 대화 기록, 세션 상태, 작업 상태, 메모리, 승인 이력, 자원 연결 상태의 정본은 폰 로컬 DB다.
- 서버가 있더라도 보조 계층일 뿐이며 정본이 아니다.

### 5.2 Chat-first UX, capability-first architecture

- 사용자 경험은 **chat-first**다.
- 내부 아키텍처는 **capability-first**다.
- 사용자는 채팅으로 요청하고, 시스템은 내부적으로 capability를 조합해 실행한다.

### 5.3 Tasks, not daemons

- Makoion은 무한 agent loop를 백그라운드에서 돌리는 구조가 아니다.
- 모든 작업은 `요청 -> 계획 -> 실행 -> 중단/대기 -> 재개 -> 완료`의 태스크 모델로 다뤄야 한다.
- 앱이 죽어도 작업은 복구되어야 한다.

### 5.4 Resource-oriented execution

- 에이전트는 추상적인 “지능”이 아니라 **연결된 자원 집합을 운영하는 실행기**다.
- 자원 연결이 없는 agent는 제한된 agent다.
- 따라서 기능 개발보다 **자원 연결 모델**이 먼저 정리되어야 한다.

### 5.5 Explicit trust and approval

- 위험한 작업은 자동으로 몰래 실행하면 안 된다.
- 삭제, 외부 공유, 대량 변경, 고권한 원격 제어는 승인과 감사가 필요하다.

### 5.6 Simple product UI, rich internal system

- 제품 UI는 최대한 단순해야 한다.
- 내부 시스템은 복잡해도 된다.
- 복잡성은 채팅창 뒤에 숨겨야 한다.

---

## 6. 제품 UI/UX 원칙

### 6.1 메인 화면

메인 화면은 **거의 채팅창만 있어야 한다.**

필수 요소:

- 대화 히스토리
- 텍스트 입력창
- 전송 버튼
- 대화/음성 시작 버튼

메인 화면에 파일 탭, 기기 탭, 승인 탭을 전면 배치하지 않는다.

### 6.2 메인 네비게이션

프로덕트 기준 기본 탭은 아래를 권장한다.

1. **Chat**
   - 기본 진입점
   - 모든 요청의 시작점
2. **Dashboard**
   - 완료/실패/승인 필요/진행 중 작업 요약
3. **History**
   - 지난 대화, 실행 기록, 결과 조회
4. **Settings**
   - 자원 연결, 권한, 모델, API, MCP, companion 설정

### 6.3 디버그/운영용 화면

현재 저장소의 `Files / Devices / Approvals` 중심 화면은
최종 제품 UI가 아니라 **개발용 운영 콘솔**로 봐야 한다.

원칙:

- 개발 빌드에서만 노출하거나
- Settings 내부의 고급/디버그 섹션으로 이동하거나
- 별도 admin/debug route로 분리한다

### 6.4 UI 결정 금지 규칙

아래 방향은 피한다.

- 파일 탐색기처럼 보이는 메인 앱
- 설정 화면보다 디버그 패널이 더 먼저 보이는 구조
- 사용자가 capability 구조를 이해해야만 쓸 수 있는 UI
- agent 대신 사용자가 수동으로 작업을 조립해야 하는 UX

---

## 7. 시스템의 핵심 모델

### 7.1 Phone Agent Runtime

Makoion 런타임은 최소한 다음 컴포넌트를 가져야 한다.

- `Session Manager`
  - 대화 세션, 작업 연속성, 컨텍스트 범위 관리
- `Turn Processor`
  - 사용자 입력을 받아 계획/질문/실행으로 분기
- `Planner`
  - 자연어 요청을 실행 가능한 intent/task로 분해
- `Task Engine`
  - 실행, 대기, 재시도, 복구, 완료 관리
- `Resource Registry`
  - 연결된 자원과 각 자원의 capability 추적
- `Capability Broker`
  - 실제 실행 가능한 도구 선택과 호출
- `Policy / Approval / Audit`
  - 권한 판단, 승인 흐름, 결과 기록
- `Memory Store`
  - 사용자 선호, 작업 히스토리, 장기 기억
- `Model Router`
  - 요청 종류에 따른 모델/프로바이더 선택
- `Recovery Coordinator`
  - 앱 재시작/foreground 복귀 시 상태 복구

### 7.2 Resource Registry

에이전트가 다룰 자원은 공통 모델로 정리해야 한다.

필수 자원 유형:

- `local_storage`
- `photos`
- `documents`
- `cloud_drive`
- `desktop_companion`
- `tablet_companion`
- `mcp_server`
- `api_profile`
- `device_capability`

권장 엔티티:

- `ResourceConnection`
- `ResourceCapability`
- `ResourceCredentialRef`
- `ResourceHealthState`
- `ResourcePolicy`

### 7.3 Task 모델

태스크는 최소한 다음 상태를 가져야 한다.

- `queued`
- `planning`
- `waiting_user`
- `waiting_resource`
- `running`
- `paused`
- `retry_scheduled`
- `succeeded`
- `failed`
- `cancelled`

### 7.4 Agent 출력 계약

모델이 직접 OS API를 호출하면 안 된다.
모델은 아래 같은 상위 추상만 만든다.

- `Answer`
- `Question`
- `Plan`
- `ActionIntent`
- `Escalation`

실행은 항상 다음을 거친다.

1. 요청 해석
2. 계획 생성
3. 자원 조회
4. 정책/승인 판단
5. capability 실행
6. 결과 기록
7. 사용자 보고

---

## 8. 자원 연결 전략

### 8.1 연결 대상

Makoion이 우선적으로 연결해야 하는 자원은 아래다.

1. 폰 로컬 파일/사진/문서
2. SAF로 연결한 폴더
3. Google Drive / OneDrive / Dropbox 같은 클라우드 드라이브
4. PC/Mac companion
5. 다른 모바일/태블릿 companion
6. MCP 서버
7. 외부 API 키/프로필

### 8.2 연결 후 에이전트가 해야 하는 일

자원이 연결되면 Makoion은 최소한 아래를 알아야 한다.

- 이 자원이 무엇인지
- 어떤 capability를 제공하는지
- 지금 사용 가능한지
- 인증 상태가 정상인지
- 읽기만 가능한지, 쓰기도 가능한지
- 승인 없이는 어떤 작업이 금지되는지

### 8.3 연결 상태의 사용자 표현

Settings에서는 각 자원을 아래처럼 보여줘야 한다.

- 연결됨 / 연결 안 됨
- 사용 가능 / 제한됨 / 오류 / 재인증 필요
- 제공 capability 목록
- 마지막 점검 시각
- 최근 실패 사유

---

## 9. 아키텍처 방향

### 9.1 최상위 구조

```text
[User]
  -> [Chat / Voice UI on Phone]
  -> [Phone Agent Runtime]
       -> [Session Manager]
       -> [Planner]
       -> [Task Engine]
       -> [Resource Registry]
       -> [Capability Broker]
       -> [Policy / Approval / Audit]
       -> [Memory Store]
       -> [Model Router]
       -> [Recovery Coordinator]
  -> [Connected Resources]
       -> [Local Files / Photos / Documents]
       -> [Cloud Drives]
       -> [Desktop / Tablet Companions]
       -> [MCP Servers]
       -> [External APIs]
  -> [Optional Managed Relay]
       -> [Push / rendezvous / mailbox only]
```

### 9.2 OpenClaw와 NanoClaw에서 가져올 것

반드시 흡수할 개념:

- 세션 중심 agent runtime
- 명시적 설정 구조
- 모델 라우팅과 failover
- 태스크 스케줄링과 재시도
- durable state 저장
- capability / tool 호출의 명시적 경계

### 9.3 OpenClaw와 NanoClaw에서 그대로 가져오지 않을 것

- 다채널 메신저 중심 제품 구조
- 폰 바깥 서버가 본체인 구조
- 모바일에서 유지 불가능한 상시 daemon 전제
- 모바일 앱 안에 범용 shell/exec를 심는 방식

---

## 10. 저장소의 목표 구조

현재 저장소는 일부가 이미 존재하지만,
앞으로는 아래 구조를 목표로 수렴시킨다.

```text
Makoion/
  projects/
    apps/
      android/
      ios/
      desktop-companion/
    core/
      agent-runtime/
      task-engine/
      resource-registry/
      memory/
      policy/
      protocol/
      model-router/
    connectors/
      local-files/
      photos/
      gdrive/
      onedrive/
      desktop-companion/
      mcp/
      api-profiles/
    docs/
      architecture/
      plans/
      ux/
      security/
```

### 10.1 현재 구현을 어떻게 해석할 것인가

현재 구현의 위치는 아래처럼 해석한다.

- `projects/apps/android`
  - 현재는 Android 개발 셸 + 운영 콘솔
  - 앞으로는 제품 UI와 디버그 UI를 분리해야 함
- `projects/apps/desktop-companion`
  - companion seed
  - 자원 실행 노드의 초기 버전
- `projects/core`, `projects/db`, `projects/model_router`
  - agent runtime 코어로 재편될 기반

---

## 11. 개발 단계별 마스터 로드맵

이 로드맵은 “무엇부터 만들 것인가”에 대한 기준이다.

### Track 0 — 방향 재정렬

목표:

- 제품 정의를 `phone-hosted AI agent`로 고정
- 기존 파일 중심 셸을 개발용 콘솔로 재해석
- 제품 UI를 chat-first로 재설계

필수 산출물:

- 이 마스터 플랜 문서
- 제품 정보 구조도
- Android 프로덕트 UI 초안
- 디버그 UI 분리 원칙

완료 기준:

- 모든 신규 작업이 이 문서를 기준으로 설명 가능
- “왜 Files 탭이 메인이 아닌가?”에 팀 모두 같은 답을 함

### Track 1 — Agent Runtime Core

목표:

- 폰 안에서 agent가 턴을 처리하고 태스크를 관리하는 코어를 만든다

필수 구현:

- session model
- turn processor
- planner output contract
- task engine
- resource registry
- policy / approval / audit
- recovery model

완료 기준:

- 텍스트 요청 1건이 `대화 -> 계획 -> 실행 -> 결과`로 이어진다
- 앱 재시작 후 in-flight task가 복구된다

### Track 2 — Android Product Shell

목표:

- 최종 사용자용 chat-first Android 앱을 만든다

필수 구현:

- Chat 탭
- Dashboard 탭
- History 탭
- Settings 탭
- 텍스트 입력
- 음성 입력
- 진행 상태 표시
- 완료 알림

금지:

- 제품 메인 화면에 파일 탐색형 구조를 유지하는 것

완료 기준:

- 사용자는 Files/Devices 화면 없이도 기본 요청을 보낼 수 있다

### Track 3 — Resource Connections P0

목표:

- agent가 실제로 사용할 자원을 연결한다

P0 연결 대상:

- 로컬 파일/사진
- SAF 폴더
- Google Drive 또는 OneDrive 중 최소 1개
- desktop companion
- API 프로필
- MCP 브리지 초안

완료 기준:

- Settings에서 연결 상태를 관리할 수 있다
- agent가 연결 자원을 조회하고 선택할 수 있다

### Track 4 — Capability Execution P0

목표:

- 연결된 자원을 사용해 실제 유용한 일을 수행한다

P0 capability:

- 파일 검색
- 파일 요약
- 파일 정리 dry-run
- 파일 전송
- 원격 폴더/앱 열기
- 세션 알림
- 작업 완료 보고

완료 기준:

- 사용자가 대화만으로 대표 작업 3개 이상을 완수한다

### Track 5 — Reliability and Safety

목표:

- 에이전트가 실제 서비스처럼 믿고 쓸 수 있게 만든다

필수 구현:

- 승인 인박스
- audit trail
- retry/backoff
- task checkpoint
- foreground / process death recovery
- background notification

완료 기준:

- 실패와 재시도 흐름이 사용자에게 설명 가능하다
- 앱 종료 후에도 상태가 사라지지 않는다

### Track 6 — Expansion

목표:

- P1 연결과 P1 실행 시나리오를 넓힌다

후속 범위:

- iOS shell
- richer MCP integration
- scheduled tasks
- persistent memory expansion
- managed relay
- smarter planning and delegation

---

## 12. 당장 진행해야 할 구현 우선순위

현재 저장소 기준으로는 아래 순서가 맞다.

### Priority 1

Android를 **chat-first shell**로 재구성한다.

필수 작업:

- 메인 화면을 채팅 중심으로 교체
- Files / Devices / Approvals 중심 구조를 제품 기본 경로에서 제거
- Dashboard / History / Settings 구조를 만든다

### Priority 2

기존 파일/기기 기능을 **agent capability**로 재포장한다.

필수 작업:

- organize, preview, transfer, app.open, workflow.run을 UI 기능이 아니라 capability 실행기로 재정의
- 기존 Android UI에서 직접 부르던 흐름을 agent runtime 경유로 옮긴다

### Priority 3

`Resource Registry`를 도입한다.

필수 작업:

- 로컬 파일
- 클라우드 드라이브
- companion
- MCP
- API 프로필

이 다섯 범주를 같은 연결 모델로 묶는다.

### Priority 4

`Turn Processor -> Planner -> Task Engine` 경로를 실제 사용자 입력과 연결한다.

### Priority 5

기존 디버그 검증 스크립트는 유지하되,
제품 UI 기준 검증 시나리오를 별도로 추가한다.

---

## 13. 승인, 보안, 감사 원칙

### 13.1 기본 승인 대상

- 외부 전송
- 삭제
- 대량 이동/이름 변경
- 클라우드 업로드
- 원격 기기에서의 고권한 실행
- API 비용이 큰 요청

### 13.2 감사 로그 최소 항목

- 사용자 요청
- 생성된 계획 또는 intent
- 사용한 자원
- 사용한 capability
- 승인 여부
- 최종 결과
- 실패 원인
- 재시도 가능 여부

### 13.3 보안 금지 사항

- 모바일 앱 내부 범용 shell 노출
- 민감 credential의 평문 저장
- 정본 상태를 외부 서버에 두는 설계
- 사용자 승인 없이 destructive action 자동 실행

---

## 14. 기술 선택 원칙

### 14.1 플랫폼

- Android: Kotlin + Jetpack Compose
- iOS: Swift + SwiftUI
- DB: SQLite
- 암호화: Android Keystore / Apple Keychain

### 14.2 코어 공유 전략

권장:

- 도메인 코어 공유
- 플랫폼 셸은 네이티브 유지

비권장:

- UI를 무리하게 공유하는 것
- OS 통합까지 범용 프레임워크에 묶는 것

### 14.3 모델/에이전트 전략

- 다중 모델 지원
- 모델 라우팅과 failover
- 요청 종류별 모델 선택
- 로컬 판단과 원격 모델 호출의 책임 분리

---

## 15. 작업 수주 규칙

앞으로 누가 어떤 작업을 하든, 작업 시작 전에 아래를 명시해야 한다.

1. 이 작업은 어떤 사용자 요청 경험을 강화하는가
2. 이 작업은 어느 Track에 속하는가
3. 이 작업은 어떤 자원 또는 capability에 영향을 주는가
4. 이 작업은 제품 UI인가, 디버그 UI인가
5. 검증 방법은 무엇인가
6. 이 문서의 어떤 섹션과 연결되는가

이 6가지를 설명하지 못하면 우선 개발하지 않는다.

---

## 16. 완료 기준

Makoion이 올바른 방향으로 가고 있다는 신호는 아래다.

1. 사용자는 메인 화면에서 채팅과 음성만으로 대부분의 작업을 시작한다.
2. agent는 사용자의 연결 자원을 이해하고 스스로 적절한 자원을 선택한다.
3. 파일, 드라이브, MCP, API, companion이 모두 같은 자원 모델 아래 관리된다.
4. 사용자는 “내 폰에 내 AI agent가 있다”고 느낀다.
5. 제품 UI는 단순하지만, 내부적으로는 복구 가능한 task system이 존재한다.
6. 디버그 콘솔 없이도 핵심 사용자 시나리오를 수행할 수 있다.

---

## 17. 절대 하지 말아야 할 것

- OpenClaw Gateway를 폰으로 그대로 복사하는 것
- 파일 탐색형 UI를 제품 메인으로 유지하는 것
- 모바일에서 유지 불가능한 무한 background agent를 전제하는 것
- 자원 연결 모델 없이 기능별로 제각각 붙이는 것
- agent runtime을 건너뛰고 UI 버튼에서 직접 실행기를 호출하는 구조를 계속 늘리는 것
- 디버그용 화면을 최종 제품 구조로 착각하는 것

---

## 18. 최종 지시

Makoion의 본질은 이것이다.

> **내 폰을 서버로 삼아, 내가 연결한 모든 자원을 대신 운용하고, 대화로 요청을 받아 끝까지 수행하는 개인 AI agent**

앞으로의 모든 설계와 구현은 이 문장을 기준으로 판단한다.

판단이 애매하면 아래 순서로 고른다.

1. 더 chat-first 한가
2. 더 agent-runtime 중심인가
3. 더 resource-oriented 한가
4. 더 복구 가능하고 감사 가능한가
5. 더 단순한 제품 UI인가

이 다섯 질문에 더 잘 맞는 쪽이 Makoion의 올바른 방향이다.
