# MobileClaw 개발 지시서 v0.2

## 문서 목적
이 문서는 **MobileClaw**를 위한 제품/아키텍처/구현 지시서다.

MobileClaw는 다음을 만족해야 한다.
- **별도 self-hosted 서버 구축 없이** 동작한다.
- **폰이 주 허브**가 된다.
- **로컬 파일 + 클라우드 파일 + 다른 기기**를 하나의 작업 표면으로 묶는다.
- 시작은 **Android-first**지만, **첫날부터 iOS 호환성**을 고려한 코어 구조를 가진다.
- 목표는 단순 채팅 앱이 아니라, **유비쿼터스 개인 작업 운영체제**다.

---

## 1. 제품 한 줄 정의
**MobileClaw = Phone Hub + Unified File Graph + Companion Nodes + Optional Relayless/Managed Connectivity**

사용자의 폰이 AI 비서의 주체이며,
모든 파일/작업/기기 제어는 폰을 중심으로 계획되고 실행된다.

서버는 필수가 아니다.
필요하다면 푸시, 웹훅, 장시간 작업 위임, 원격 연결 중계만 담당하는 **초소형 관리형 보조 계층**만 둔다.

---

## 2. 북극성 목표
### 2.1 핵심 경험
사용자는 폰에서 다음을 자연어/음성으로 수행할 수 있어야 한다.
- “어제 찍은 사진이랑 계약서 PDF 찾아서 거래처 폴더에 정리해줘.”
- “구글 드라이브랑 폰 문서함에 있는 세금 문서 다 모아서 요약해줘.”
- “집 PC에 있는 발표자료 열고, 패드에서 바로 이어서 볼 수 있게 보내줘.”
- “노트북에서 회의 폴더 열고 최근 문서 정리해줘.”
- “이 파일들 이름 규칙 맞춰서 정리하고 중복 제거해줘.”

### 2.2 제품 본질
이 제품은 다음 3가지를 합친다.
1. **폰 중심 AI 비서**
2. **파일 중심 개인 지식/작업 허브**
3. **다른 기기를 조종하는 유비쿼터스 오케스트레이터**

---

## 3. 비전과 비목표
### 3.1 비전
- 사용자는 더 이상 “서버에 접속해서 AI를 부르는 구조”를 의식하지 않는다.
- 사용자는 “폰에서 나의 모든 디지털 자산과 기기를 다룬다.”는 감각을 가져야 한다.
- MobileClaw는 단일 앱이 아니라, **사용자의 디지털 생활 전반을 연결하는 중심점**이 되어야 한다.

### 3.2 비목표
- OpenClaw식 중앙 Gateway를 필수 전제로 채택하지 않는다.
- 모바일 앱 안에 범용 shell/exec 환경을 넣지 않는다.
- 서버가 사용자 메모리/파일 상태의 정본(source of truth)이 되지 않는다.
- Android 전용 기능을 코어 모델에 하드코딩하지 않는다.
- “항상 살아 있는 백그라운드 데몬”을 제품 전제로 두지 않는다.

---

## 4. 핵심 설계 원칙
### 4.1 Phone is the source of truth
- 대화, 메모리, 작업 상태, 파일 인덱스, 승인 이력의 정본은 **폰 로컬 DB**다.
- 서버가 있더라도 이는 캐시, 중계, 위임 실행용 보조 계층이다.

### 4.2 File-first, not chat-first
- 채팅은 UI일 뿐이다.
- 제품의 진짜 핵심 객체는 **파일, 폴더, 컬렉션, 작업, 기기**다.
- 모든 대화는 결국 파일/작업/기기 상태를 변경하거나 탐색하기 위한 인터페이스다.

### 4.3 Tasks, not daemons
- MobileClaw는 상시 떠 있는 서버가 아니라 **이벤트 기반 작업 엔진**이다.
- OS가 앱을 죽여도 다시 복구 가능한 구조여야 한다.

### 4.4 Capability-driven architecture
- 모델은 OS 기능을 직접 호출하지 않는다.
- 항상 `Intent -> Policy -> Executor -> Audit` 경로를 거친다.

### 4.5 Ubiquitous by companions
- PC, Mac, iPad, Android tablet 등 다른 기기는 “서버”가 아니라 **Companion Node**다.
- 폰이 중심 허브이며, 다른 기기는 필요할 때만 연결되는 말단 실행기다.

### 4.6 No hidden privilege
- 위험한 작업은 항상 승인/감사/취소 가능해야 한다.
- 모델이 임의의 고권한 동작을 몰래 수행하지 못하게 한다.

---

## 5. 시스템 아키텍처
```text
[User]
  -> [Chat / Voice / Widget / Share Sheet / Notification Actions]
  -> [Phone Hub]
       -> [Session Manager]
       -> [Policy Engine]
       -> [Task Engine]
       -> [Unified File Graph]
       -> [Memory Store]
       -> [Capability Brokers]
       -> [Model Router]
       -> [Sync Engine]
       -> [Companion Router]
  -> [Cloud Providers / LLM APIs / Push Services]
  -> [Companion Nodes: PC / Mac / iPad / Tablet]
```

### 5.1 Phone Hub
폰은 다음을 맡는다.
- 사용자 UI의 중심
- 개인 상태/메모리 정본
- 액션 계획 생성
- 작업 상태 관리
- 승인/감사/정책 집행
- 파일 그래프 유지
- 다른 기기 제어의 오케스트레이션

### 5.2 Unified File Graph
서로 다른 파일 소스를 하나의 그래프로 표현한다.

소스 예시:
- 폰 사진 보관함
- 폰 파일 앱/문서함
- 앱 내 저장공간
- 다운로드 폴더
- Google Drive
- Dropbox
- OneDrive
- iCloud Drive / Files 연동 경로
- PC/Mac Companion가 노출한 파일 시스템

핵심 개념:
- `Source`
- `Asset`
- `Document`
- `Folder`
- `Collection`
- `Link`
- `Preview`
- `SemanticTag`
- `SyncCursor`

원칙:
- 실제 파일 원본이 어디 있든, MobileClaw는 **논리적 파일 그래프**로 다룬다.
- 모든 검색/요약/정리/이동/중복제거는 이 그래프 위에서 작동한다.

### 5.3 Companion Nodes
다른 기기를 제어하려면, 그 기기에 Companion Node를 둔다.

Companion Node 역할:
- 파일 브라우징/전송
- 앱 열기/워크플로 시작
- 화면 공유/상태 보고(선택)
- 로컬 자동화 실행
- 작업 결과를 폰 허브에 반환

중요:
- Companion Node는 **항상 켜진 중앙 서버가 아니다**.
- 특정 PC나 패드에 설치된 사용자의 말단 에이전트다.
- Phone Hub가 제어권을 가진다.

### 5.4 Optional Connectivity Layer
연결 모드는 3개를 지원한다.

1. **Direct Local Mode**
   - 같은 LAN/근거리 환경에서 직접 연결

2. **Managed Connectivity Mode**
   - 사용자 self-host 없이도 원격 연결
   - 푸시, rendezvous, NAT traversal, mailbox를 관리형 서비스가 보조

3. **Offline Queue Mode**
   - 기기가 오프라인이면 작업을 큐잉하고, 온라인 복귀 시 재개

---

## 6. 핵심 도메인 모델
### 6.1 주요 엔티티
- `UserProfile`
- `Conversation`
- `Message`
- `Task`
- `TaskCheckpoint`
- `ApprovalRequest`
- `MemoryItem`
- `Device`
- `DeviceCapability`
- `FileSource`
- `FileNode`
- `FileVersion`
- `FilePreview`
- `FileEmbedding`
- `RemoteSession`
- `AuditEvent`
- `SyncState`

### 6.2 Task 상태
- `queued`
- `ready`
- `running`
- `waiting_user`
- `waiting_device`
- `waiting_network`
- `delegated`
- `succeeded`
- `failed`
- `cancelled`

### 6.3 Capability 상태
- `supported`
- `limited`
- `denied`
- `unavailable`
- `needs_pairing`

---

## 7. 작업 실행 모델
모델은 직접 OS API를 호출하지 않는다.
모델이 생성할 수 있는 것은 아래뿐이다.
- `Answer`
- `Question`
- `Draft`
- `ActionIntent`
- `Escalation`

예시:
```json
{
  "type": "ActionIntent",
  "action": "files.organize",
  "args": {
    "targets": ["source://photos/recent", "source://gdrive/contracts"],
    "strategy": "group_by_project_and_date",
    "dry_run": true
  },
  "risk": "medium",
  "requires_confirmation": true
}
```

실행 순서:
1. 모델이 intent 제안
2. Policy Engine이 권한/리스크/플랫폼 지원 검증
3. 필요 시 사용자 승인
4. Executor가 로컬 브로커 또는 Companion Node 호출
5. 결과를 Audit Log와 Memory Store에 기록
6. 실패 시 checkpoint 기반으로 재시도

---

## 8. 파일 중심 아키텍처
### 8.1 파일을 1급 객체로 취급할 것
파일은 단순 첨부물이 아니다.
다음 연산이 기본 제공되어야 한다.
- 검색
- 분류
- 미리보기
- 요약
- 태깅
- 이름 규칙 정리
- 이동/복사
- 컬렉션 생성
- 중복 탐지
- 공유
- 다른 기기로 전달
- 후속 작업 트리거

### 8.2 사진/문서 특화 파이프라인
사진과 문서는 제품의 핵심 자산이다.

반드시 구현할 것:
- 사진/문서 manifest 수집
- 썸네일/미리보기 캐시
- 메타데이터 추출
- 날짜/위치/프로젝트/인물/문서 유형 등 semantic tagging
- “찾기 쉬운” 검색 인덱스
- 클라우드와 로컬 원본의 동일성 추적

### 8.3 destructive action 원칙
다음 작업은 기본적으로 dry-run 후 승인 받는다.
- 삭제
- 대량 이동
- 대량 이름 변경
- 폴더 구조 재편
- 중복 제거 결과 반영
- 외부 전송

### 8.4 File Graph API
모든 파일 작업은 다음 수준의 추상화로 호출한다.
- `files.search`
- `files.preview`
- `files.summarize`
- `files.organize`
- `files.move`
- `files.copy`
- `files.share`
- `files.dedupe`
- `files.send_to_device`
- `files.request_from_device`

---

## 9. 원격 기기 제어 아키텍처
### 9.1 원칙
- 폰은 **오케스트레이터**다.
- PC/Mac/iPad/Tablet은 **실행 노드**다.
- 제어는 capability 선언 기반이다.

### 9.2 Companion Node가 제공할 수 있는 capability
공통:
- `files.list`
- `files.read_metadata`
- `files.transfer`
- `app.open`
- `session.ping`
- `session.notify`

데스크톱 우선:
- `window.focus`
- `workflow.run`
- `screen.stream`
- `clipboard.read/write`
- `download.fetch`

태블릿 우선:
- `canvas.open`
- `file.receive`
- `handoff.start`
- `preview.display`

### 9.3 기기 제어 UX
사용자는 폰에서 이렇게 말할 수 있어야 한다.
- “맥북에서 발표 폴더 열어줘.”
- “패드로 이 PDF 보내고 바로 열어줘.”
- “집 PC에서 회계 폴더 압축해서 폰으로 가져와.”
- “노트북에 있는 오늘 다운로드 파일들 정리해줘.”

### 9.4 iOS/iPad 고려사항
- 태블릿 제어는 **동반 앱 기반**으로 설계한다.
- 플랫폼에 따라 자동화 수준이 다를 수 있으므로, capability discovery를 절대 생략하지 않는다.
- iPad는 “완전한 원격 OS 제어”보다, **파일 수신/열기/표시/작업 이어받기** 중심으로 설계한다.

---

## 10. 플랫폼 전략
## 10.1 Android-first, iOS-compatible
반드시 지킬 규칙:
1. Android에서 먼저 출시한다.
2. 하지만 core contracts는 iOS에서도 성립해야 한다.
3. Android-only 기능은 capability flag 뒤에 숨긴다.
4. iOS 대체 UX가 없는 기능은 P0 핵심 가치로 두지 않는다.

### 10.2 공통 P0 기능
양 플랫폼에서 궁극적으로 제공할 핵심:
- 채팅
- 음성 입력/출력
- 파일/사진/문서 검색
- 로컬 + 클라우드 파일 요약
- 리마인더/캘린더 연동
- 공유 시트 진입
- 다른 기기로 파일 보내기
- 승인 인박스
- 작업 재개

### 10.3 Android 우선 기능
- 더 적극적인 voice/talk mode
- 알림 연동
- 화면 캡처
- 기기 상태 노출
- 고급 자동화
- 외부 앱 워크플로 트리거

### 10.4 iOS 준비 원칙
- iOS는 “축소판”이 아니라 **동일한 철학의 다른 실행 표면**으로 취급한다.
- 처음부터 iOS용 데이터 모델, capability 스키마, task state machine을 공유한다.
- Android 기능은 iOS 미지원 시 graceful degradation 하도록 작성한다.

---

## 11. 백그라운드 실행 설계
### 11.1 공통 철학
- 장시간 상시 프로세스를 전제로 설계하지 않는다.
- 앱이 죽을 수 있다는 가정 하에서 복구 가능하게 만든다.
- 장시간 작업은 foreground, OS task, background transfer, delegated job으로 나눈다.

### 11.2 작업 분류
1. **Immediate Local**
   - 즉시 로컬 처리
   - 예: 최근 파일 검색, 메모리 조회

2. **Interactive Foreground**
   - 사용자 대화/음성 세션
   - 예: 사진 보고 요약, 파일 정리 승인

3. **Bounded Background Maintenance**
   - 색인 갱신, 썸네일 정리, sync cursor 업데이트

4. **OS-owned Transfer**
   - 대용량 업로드/다운로드

5. **Delegated Remote Job**
   - PC Companion 또는 관리형 보조 계층에 위임

### 11.3 설계 금지
- 영구 소켓을 배터리 소모 감수하고 기본값으로 유지
- 백그라운드에서 끝없이 agent loop 수행
- 앱이 살아 있다는 가정에 의존한 task 설계

---

## 12. 음성 아키텍처
### 12.1 목표
MobileClaw의 음성 경험은 폰을 허브로 만드는 핵심 UX다.

### 12.2 파이프라인
1. wake word 또는 push-to-talk
2. VAD
3. ASR
4. intent classification
5. file/device context fetch
6. plan generation
7. policy check
8. action execution
9. TTS 응답
10. session summary 저장

### 12.3 규칙
- wake word는 가능하면 로컬 우선
- network 장애 시에도 최소한 음성 메모/로컬 큐 적재는 가능해야 한다
- 장시간 음성 세션은 사용자에게 분명히 보이는 상태여야 한다

---

## 13. Policy / Approval / Audit
### 13.1 리스크 등급
- Low: 검색, 요약, 초안 생성
- Medium: 일정 생성, 파일 이동 제안, 폴더 분류
- High: 삭제, 대량 변경, 외부 전송, 원격 제어 실행

### 13.2 승인 원칙
다음은 기본 승인 대상이다.
- 메시지/메일 발송
- 파일 삭제
- 대량 이동/이름변경
- 다른 기기에서의 고권한 작업 실행
- 클라우드 업로드
- 외부 공유

### 13.3 감사 로그
모든 액션은 다음을 남긴다.
- 누가 요청했는지
- 어떤 intent였는지
- 어떤 capability를 썼는지
- 어떤 결과였는지
- 되돌릴 수 있는지

---

## 14. 보안 지시
1. 로컬 DB는 OS 보안 저장소와 연동된 키로 암호화한다.
2. 민감 데이터는 클라우드 전송 전 최소화/마스킹한다.
3. Companion pairing은 QR + device trust model로 설계한다.
4. 고위험 capability는 세션 단위 재승인을 허용한다.
5. generated skill은 코드 실행이 아니라 선언형 tool graph로 우선 설계한다.
6. 모바일 앱에는 범용 shell/exec 도구를 두지 않는다.
7. 릴레이가 있더라도 memory 정본과 파일 정본을 소유하지 않게 한다.

---

## 15. 기술 스택 권고
### 권장 구조
- Android shell: Kotlin + Jetpack Compose
- iOS shell: Swift + SwiftUI
- Shared core: Kotlin Multiplatform 또는 Rust core
- Local DB: SQLite 공통 스키마
- Encryption: Android Keystore / Apple Keychain
- Push: FCM / APNs
- Companion desktop: macOS/Windows/Linux 별 경량 네이티브 에이전트

### 설계 선택 원칙
- UI만 공유하고 OS 통합은 네이티브로 두는 방식보다,
  **도메인 코어를 공유하고 플랫폼 셸을 네이티브로 유지**하는 쪽을 우선 고려한다.
- 플랫폼 서비스까지 한 번에 추상화하는 범용 프레임워크 의존은 피한다.

---

## 16. 저장소 구조 제안
```text
mobileclaw/
  projects/
    apps/
      android/
      ios/
      desktop-companion/
    core/
      domain/
      task-engine/
      file-graph/
      memory/
      policy/
      sync/
      protocol/
    providers/
      gdrive/
      dropbox/
      onedrive/
      local-files/
      photos/
    capabilities/
      camera/
      calendar/
      contacts/
      reminders/
      location/
      voice/
      share/
      notifications/
    relay/
      rendezvous/
      mailbox/
      delegation/
  docs/
    architecture/
    protocol/
    ux/
    security/
```

---

## 17. 단계별 구현 순서
### Phase 0 — 코어 계약 확정
반드시 먼저 만들 것:
- Task state machine
- Capability schema
- Unified File Graph schema
- Device pairing protocol
- Approval/Audit model
- Android/iOS 공통 테스트 벡터

### Phase 1 — Android MVP
반드시 포함:
- 채팅
- 음성 입력
- 사진/문서/파일 인덱싱
- 로컬 + 클라우드 파일 검색
- 파일 요약
- 파일 정리 dry-run
- 승인 인박스
- 다른 기기로 파일 보내기 초안

### Phase 2 — Companion Desktop
반드시 포함:
- 폰에서 PC/Mac pairing
- 원격 파일 목록 조회
- 파일 전송
- 앱 열기/폴더 열기
- 결과 리포트

### Phase 3 — iOS Shell
반드시 포함:
- 동일한 데이터 모델 사용
- 파일 검색/요약/승인 인박스
- 다른 기기와 handoff
- 음성/공유 시트 기반 진입

### Phase 4 — Ubiquitous Flows
반드시 포함:
- “폰에서 PC 문서 가져오기”
- “폰에서 패드로 보내고 열기”
- “클라우드 + 로컬 사진/문서 일괄 정리”
- “원격 기기 작업 예약/재개/결과 확인”

---

## 18. 성공 기준
이 항목을 만족하면 방향이 맞다.

1. 사용자가 self-hosted 서버를 세팅하지 않아도 핵심 경험을 쓸 수 있다.
2. 폰만으로도 파일/사진/문서 검색과 정리가 가능하다.
3. 클라우드 파일과 로컬 파일이 하나의 그래프처럼 다뤄진다.
4. 다른 기기를 “서버”가 아니라 “내 다른 손발”처럼 제어할 수 있다.
5. 앱이 죽어도 작업이 복구된다.
6. Android로 시작했지만 iOS 확장이 구조적으로 막혀 있지 않다.
7. 사용자가 “내 폰이 곧 내 개인 AI 허브다”라고 느낀다.

---

## 19. 절대 하지 말아야 할 것
- OpenClaw Gateway를 폰으로 그냥 옮기는 방식
- 모바일 안에서 범용 exec/shell을 열어두는 방식
- 서버가 없으면 아무 것도 못 하는 구조
- 파일을 첨부물 취급하고, 제품 중심을 대화창에만 두는 설계
- Android 기능을 공통 모델로 위장하는 설계
- 원격 기기를 중앙 서버처럼 취급하는 구조

---

## 20. 최종 지시
MobileClaw의 본질은 **“서버 없는 모바일 AI 앱”**이 아니다.
MobileClaw의 본질은 다음이다.

> **폰을 중심으로, 내 파일과 내 기기와 내 클라우드를 하나의 살아있는 작업 표면으로 묶는 유비쿼터스 AI 허브**

개발팀은 모든 의사결정을 이 문장에 맞춰 내려라.
- 더 서버 같아지는가? 그러면 틀린 방향이다.
- 더 폰 중심/파일 중심/기기 오케스트레이션 중심이 되는가? 그러면 맞는 방향이다.
