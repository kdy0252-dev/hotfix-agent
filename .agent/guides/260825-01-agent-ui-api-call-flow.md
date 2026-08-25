# 기능별 Agent 및 UI API 호출 흐름

## 1. 문서 목적

이 문서는 현재 코드 기준으로 한 기능의 처음부터 끝까지를 다음 순서로 설명한다.

```text
기능 → UI API URI → Controller → Service/UseCase → Port/Adapter
     → Embabel Agent → Action → Goal → Skill/Tool → 저장 결과
```

정본 agent 목록과 capability 제한은 `app/src/main/resources/agent-capabilities.json`에 있다. LLM
agent는 외부 시스템이나 shell을 직접 호출하지 않는다. Jenkins, Grafana, Bitbucket, Git, Gradle
접근은 application service가 typed port를 통해 수행한다.

## 2. 실패한 PR 목록 조회

### 기능

Jenkins의 multibranch job과 Bitbucket PR을 대조해 실패한 PR, branch, build를 표시한다.

### 호출 흐름

```text
GET /ui/fragments/pull-requests
  → DashboardController.failedPullRequests
  → DashboardQueryService.getFailedPullRequests
  → IncidentDashboardPort.failedPullRequests
  → IncidentDashboardModuleAdapter
  → IncidentDashboardGateway.failedPullRequests
  → IncidentDashboardModuleApiService.failedPullRequests
  → QueryIncidentDashboardUseCase.getFailedPullRequests
  → JenkinsRestAdapter + Bitbucket adapter
  → 실패 PR fragment
```

### Agent, Action, Goal, Skill, Tool

- Agent/Action/Goal/Skill: 없음. 확정적인 외부 상태 조회이므로 LLM 판단을 사용하지 않는다.
- Tool: `JenkinsEvidencePort`/`JenkinsRestAdapter`, Bitbucket 조회 adapter
- 결과: 실패 PR 카드와 Bitbucket/Jenkins 링크

## 3. Jenkins 실패 빌드 AI 분석

### 기능

선택된 Jenkins build와 PR source commit을 기준으로 원인 후보를 만든다. HTTP 응답을 기다리지 않고
background task로 실행하며 상태를 DB에 저장한다.

### 호출 흐름

```text
POST /ui/analyses/jenkins
  → DashboardController.requestJenkinsAnalysis
  → DashboardQueryService.requestJenkinsAnalysis
  → IncidentDashboardPort.requestJenkinsAnalysis
  → IncidentDashboardModuleAdapter
  → IncidentDashboardGateway.requestJenkinsAnalysis
  → IncidentDashboardModuleApiService.requestJenkinsAnalysis
  → AnalyzeIncidentUseCase.analyzeJenkins
  → IncidentAnalysisService → TaskExecutor → IncidentAnalysisExecutor.execute
      1. JenkinsEvidencePort로 build metadata/console/test report 수집
      2. SourceRevisionPort로 PR의 고정 commit 확인
      3. SourceContextPort로 제한된 source context 수집
      4. EmbabelCandidateAnalysisAdapter
      5. incident-analysis-agent
  → IncidentStatePort로 분석과 후보 저장
```

동일 기능의 JSON API는 `POST /api/v1/analyses/jenkins`이며 진입점만
`IncidentController.analyzeJenkins`로 다르고 같은 `AnalyzeIncidentUseCase`를 사용한다.

### Agent, Action, Goal, Skill, Tool

- Agent: `incident-analysis-agent` (`IncidentAnalysisAgent`)
- 역할: 제한된 Jenkins 실패 증거를 독립적인 원인 후보로 변환
- Action 1: `Triage bounded Jenkins failure evidence`
- Action 2: `Turn a typed triage summary into independent bug candidates`
- Goal: `Prepare selectable, evidence-grounded bug candidates`
- Skill: `root-cause-analysis`, `source-context-selection`, `evidence-redaction`
- Agent 직접 tool: 없음
- Workflow tool:
  - `JenkinsEvidencePort` → `JenkinsRestAdapter`
  - `SourceRevisionPort` → `BitbucketSourceRevisionAdapter`
  - `SourceContextPort` → `BitbucketSourceContextAdapter`
  - `IncidentStatePort` → `JpaIncidentStatePersistenceAdapter`
- 결과: 분석 상태와 `BugCandidate` 목록

## 4. Grafana 운영 신호 목록 조회

### 기능

입력한 PROD 시간 범위에서 대상 서비스의 WARN 이상 Loki 로그와 연계 가능한 Tempo, Prometheus,
alert 증거를 페이지 단위로 표시한다.

### 호출 흐름

```text
GET /ui/fragments/observability?environment=PROD&startAt=...&endAt=...
  → DashboardController.observability
  → DashboardQueryService.getObservabilitySignals
  → IncidentDashboardPort.observabilitySignals
  → IncidentDashboardModuleAdapter
  → IncidentDashboardGateway.observabilitySignals
  → IncidentDashboardModuleApiService.observabilitySignals
  → QueryIncidentDashboardUseCase.getObservabilitySignals
  → ObservabilityEvidencePort → GrafanaObservabilityAdapter
      1. Loki WARN 이상 조회
      2. trace ID가 있으면 Tempo 링크 구성
      3. Prometheus/alert 증거 결합
  → 관측 신호 fragment
```

### Agent, Action, Goal, Skill, Tool

- Agent/Action/Goal/Skill: 없음. 조회와 필터링은 typed query로 처리한다.
- Tool: `ObservabilityEvidencePort`/`GrafanaObservabilityAdapter`
- 제한: PROD, 환경변수로 지정한 대상 서비스, 최대 31일, 사용자 표시 시각 KST
- 결과: WARN/ERROR 신호 카드와 Loki 또는 Tempo 링크

## 5. 관측 신호 AI 분석

### 기능

관측 신호와 선택 시간 범위, `main` source를 기준으로 원인 후보를 만든다. trace ID가 없어도 Loki
원문을 증거로 사용할 수 있다.

### 호출 흐름

```text
POST /ui/analyses/observability
  → DashboardController.requestObservabilityAnalysis
  → DashboardQueryService.requestObservabilityAnalysis
  → IncidentDashboardPort.requestObservabilityAnalysis
  → IncidentDashboardModuleAdapter
  → IncidentDashboardGateway.requestObservabilityAnalysis
  → IncidentDashboardModuleApiService.requestObservabilityAnalysis
  → AnalyzeIncidentUseCase.analyzeObservability
  → IncidentAnalysisService → TaskExecutor → IncidentAnalysisExecutor.execute
      1. ObservabilityEvidencePort로 Loki/Tempo/Prometheus/alert 증거 수집
      2. SourceRevisionPort로 main 고정 commit 확인
      3. SourceContextPort로 제한된 source context 수집
      4. EmbabelCandidateAnalysisAdapter
      5. incident-analysis-agent
  → IncidentStatePort로 분석과 후보 저장
```

동일 기능의 JSON API는 `POST /api/v1/analyses/observability`이다.

### Agent, Action, Goal, Skill, Tool

- Agent: `incident-analysis-agent` (`IncidentAnalysisAgent`)
- 역할: 관측 증거와 source context를 코드 수정 후보로 변환
- Action 1: `Triage bounded target service observability evidence`
- Action 2: `Turn a typed triage summary into independent bug candidates`
- Goal: `Prepare selectable, evidence-grounded bug candidates`
- Skill: `root-cause-analysis`, `source-context-selection`, `evidence-redaction`
- Agent 직접 tool: 없음
- Workflow tool: `GrafanaObservabilityAdapter`, `BitbucketSourceRevisionAdapter`,
  `BitbucketSourceContextAdapter`, `JpaIncidentStatePersistenceAdapter`
- 결과: 관측 분석 workflow 카드와 `BugCandidate` 목록

## 6. 원인 후보 정밀 AI 분석

### 기능

초기 후보의 코드 위치나 근거가 부족할 때 후보 하나를 고정 source commit의 실제 파일과 다시
대조한다. 정밀 결과가 자동 수정 조건을 만족하면 Draft PR 버튼이 활성화된다.

### 호출 흐름

```text
POST /ui/analyses/{analysisId}/candidates/{candidateId}/refinement
  → DashboardController.refineCandidate
  → DashboardQueryService.refineCandidate
  → IncidentDashboardPort.refineCandidate
  → IncidentDashboardModuleAdapter
  → IncidentDashboardGateway.refineCandidate
  → IncidentDashboardModuleApiService.refineCandidate
  → RefineCandidateUseCase.refine
  → CandidateRefinementService → TaskExecutor → CandidateRefinementExecutor.execute
      1. SourceContextPort로 후보 관련 파일/라인 재탐색
      2. CandidateRefinementPort
      3. candidate-refinement-agent
  → 정밀 후보와 task 상태 저장
```

동일 기능의 JSON API는
`POST /api/v1/analyses/{analysisId}/candidates/{candidateId}/refinement`이다.

### Agent, Action, Goal, Skill, Tool

- Agent: `candidate-refinement-agent` (`CandidateRefinementAgent`)
- 역할: 후보 원인, 파일, 라인, 자동 수정 가능성을 fresh source와 대조
- Action: `Verify one candidate against exact source locations`
- Goal: `Produce one more precise evidence-grounded candidate`
- Skill: `candidate-evidence-recheck`, `source-context-selection`, `evidence-redaction`
- Agent 직접 tool: 없음
- Workflow tool: `SourceContextPort`/`BitbucketSourceContextAdapter`, refinement task persistence
- 결과: 갱신된 confidence, source locations, selectable 정책

## 7. 후보 선택과 Draft PR 생성

### 기능

원인 후보 하나를 선택하면 전용 worktree에서 patch를 만들고, 로컬 집중 검증과 Jenkins parity 검증,
독립 AI review를 통과한 경우에만 Bitbucket Draft PR을 만든다. 후보 카드의 `수정 방향`은 선택
사항이며 비어 있으면 기존 방식으로 원인 증거에 따라 수정한다.

### 호출 흐름

```text
POST /ui/analyses/{analysisId}/selections
  → DashboardController.selectCandidate
  → DashboardQueryService.selectCandidate
  → IncidentDashboardPort.selectCandidate
  → IncidentDashboardModuleAdapter
  → IncidentDashboardGateway.selectCandidate
  → IncidentDashboardModuleApiService.selectCandidate
  → SelectCandidateUseCase.select
  → HotfixSelectionService
      1. idempotency/version/TTL/selectable/source freshness 검사
      2. patchInstruction 저장
      3. TaskExecutor에 background workflow 등록
  → GuardedHotfixWorkflowAdapter.execute
      1. PatchWorkspacePort.prepare
      2. PatchProposalPort.propose → patch-author-agent
      3. PatchWorkspacePort.apply
      4. VerificationPort.runFocused
      5. PatchReviewPort.review → patch-review-agent
      6. VerificationPort.runParity
      7. PullRequestPort.publishDraft
  → IncidentStatePort로 단계별 진행 상태와 결과 저장
```

동일 기능의 JSON API는 `POST /api/v1/analyses/{analysisId}/selections`이다. JSON body의
`patchInstruction`도 선택 사항이며 최대 2,000자다.

### Agent 1: patch 작성

- Agent: `patch-author-agent` (`PatchAuthorAgent`)
- 역할: 선택 후보를 해결하는 최소 파일 replacement 제안. 정책 한도 안에서 여러 파일 수정 가능
- Action: `Propose complete replacement content for allowed source files`
- Goal: `Create a bounded patch proposal for the selected candidate`
- Skill: `minimal-patch-proposal`, `evidence-redaction`
- Agent 직접 tool: 없음

### Agent 2: 독립 patch 검토

- Agent: `patch-review-agent` (`PatchReviewAgent`)
- 역할: 적용된 diff와 검증 결과를 작성 agent와 독립적으로 검토
- Action: `Review patch evidence independently without modifying files`
- Goal: `Approve or reject an applied hotfix patch`
- Skill: `independent-patch-review`, `evidence-redaction`
- Agent 직접 tool: 없음

### Workflow Tool

- `PatchWorkspacePort` → `LocalGitPatchWorkspaceAdapter`: worktree, patch 적용, commit, branch push
- `VerificationPort` → `LocalJenkinsParityVerificationAdapter`: focused/parity Gradle 검증
- `PullRequestPort` → `BitbucketDraftPullRequestAdapter`: Draft PR 생성
- `IncidentStatePort` → `JpaIncidentStatePersistenceAdapter`: background 상태 저장

### 수정 방향 전달 경로

```text
후보 카드 textarea 또는 AI 챗봇 후속 문장
  → patchInstruction
  → DashboardUseCase.SelectionCommand
  → IncidentDashboardGateway.SelectionCommand
  → SelectCandidateUseCase.SelectionCommand.PatchInstruction
  → HotfixResource.patchInstruction
  → hotfix_agent.incident_hotfixes.patch_instruction
  → HotfixWorkflowPort.execute
  → PatchProposalPort.PatchRequest → PatchAuthorInput
  → patch-author-agent prompt의 Optional user patch direction
```

- 미입력/공백: `PatchInstruction.none()`이며 기존 prompt 동작을 유지한다.
- 입력: trim 후 저장되며 서버 재기동과 workflow 복구 후에도 유지한다.
- 후보가 하나면 챗봇에 `중복 요청이면 기존 결과를 반환하도록 수정해줘`처럼 번호 없이 입력한다.
- 후보가 여러 개면 `1번은 기존 결과를 반환하도록 수정해줘`처럼 번호를 포함한다.
- 지침은 안전 정책, 금지 파일, 변경량 제한, 검증 절차를 우회할 수 없다.

## 8. 사람 수정 branch 게시와 재검증

### 기능

자동 patch가 사람 검토로 전환되면 review branch를 게시한다. 사람이 commit/push한 변경은 다시
focused test, AI review, parity test를 거쳐 Draft PR 생성 흐름을 재개한다.

### 호출 흐름

```text
POST /ui/hotfixes/{hotfixId}/human-review-branch
  → DashboardController.publishHumanReviewBranch
  → DashboardQueryService.publishHumanReviewBranch
  → ManageHotfixUseCase.publishHumanReviewBranch
  → HotfixManagementService
  → HotfixWorkflowPort.publishForHumanReview
  → LocalGitPatchWorkspaceAdapter

POST /ui/hotfixes/{hotfixId}/human-changes-verification
  → DashboardController.verifyHumanChanges
  → DashboardQueryService.verifyHumanChanges
  → ManageHotfixUseCase.verifyHumanChanges
  → HotfixManagementService → TaskExecutor
  → GuardedHotfixWorkflowAdapter.verifyHumanChanges
      1. PatchWorkspacePort.reloadHumanChanges
      2. VerificationPort.runFocused
      3. PatchReviewPort → patch-review-agent
      4. VerificationPort.runParity
      5. PullRequestPort.publishDraft
```

### Agent, Action, Goal, Skill, Tool

- Agent: `patch-review-agent`
- Action: 사람이 수정한 patch의 독립 검토
- Goal: 검증된 patch만 Draft PR 게시 단계로 통과
- Skill: `independent-patch-review`, `evidence-redaction`
- Tool: Git worktree adapter, Gradle verification adapter, Bitbucket Draft PR adapter
- 결과: 사람 검토 대기 유지 또는 Draft PR 생성

## 9. Hotfix 재시작, CI 갱신, 삭제

### 호출 흐름

```text
POST /ui/hotfixes/{hotfixId}/restarts
  → DashboardController.restartHotfix
  → DashboardQueryService.restartHotfix
  → ManageHotfixUseCase.restart → HotfixManagementService
  → 기존 candidate와 patchInstruction을 보존해 SelectCandidateUseCase 재실행

POST /ui/hotfixes/{hotfixId}/ci-refresh
  → DashboardController.refreshHotfixCi
  → DashboardQueryService.refreshHotfixCi
  → QueryHotfixUseCase.refreshCiStatus
  → JenkinsEvidencePort로 현재 상태 1회 조회

DELETE /ui/hotfixes/{hotfixId}
  → DashboardController.cancelAndDeleteHotfix
  → DashboardQueryService.cancelAndDeleteHotfix
  → ManageHotfixUseCase.cancelAndDeleteHotfix
  → 실행 취소 + 로컬 hotfix 상태 삭제

DELETE /ui/workflows/{analysisId}
  → DashboardController.cancelAndDeleteWorkflow
  → DashboardQueryService.cancelAndDeleteWorkflow
  → ManageHotfixUseCase.cancelAndDeleteWorkflow
  → 연결된 실행 취소 + 로컬 analysis/hotfix 상태 삭제
```

### Agent, Action, Goal, Skill, Tool

- 재시작: 7절의 `patch-author-agent`와 `patch-review-agent` 재사용
- CI 갱신/삭제: Agent/Action/Goal/Skill 없음
- Tool: Jenkins adapter, `IncidentStatePort`, `HotfixExecutionRegistry`

## 10. AI 챗봇 요청 해석과 실행

### 기능

동일 챗봇 대화에서 최근 실패 PR 조회, 분석, 후보 선택, 수정 방향 지정, 우선순위 조회 요청을 typed
command로 해석한다. 해석만으로 write 작업을 시작하지 않고 확인 또는 후보 선택 뒤 기존 UI API를
호출한다.

### 호출 흐름

```text
POST /ui/natural-language/interpretations
  → DashboardController.interpretNaturalLanguage
  → DashboardQueryService.interpretNaturalLanguage
  → NaturalLanguageDashboardPort.interpret
  → NaturalLanguageDashboardModuleAdapter
  → NaturalLanguageCommandService.interpret
  → EmbabelNaturalLanguageInterpreterAdapter
  → natural-language-command-agent
  → 해석 preview 저장 및 UI 반환

POST /ui/natural-language/interpretations/{interpretationId}/executions
  → DashboardController.executeNaturalLanguage
  → DashboardQueryService.executeNaturalLanguage
  → NaturalLanguageDashboardPort.execute
  → NaturalLanguageCommandService.execute
  → version/hash/TTL 확인
  → ConfirmedCommandDispatchPort
  → 기존 Jenkins/관측/후보/상태 typed use case 실행
```

JSON API는 다음과 같다.

- `POST /api/v1/natural-language/interpretations`
- `GET /api/v1/natural-language/interpretations/{interpretationId}`
- `POST /api/v1/natural-language/interpretations/{interpretationId}/executions`

### Agent, Action, Goal, Skill, Tool

- Agent: `natural-language-command-agent` (`NaturalLanguageCommandAgent`)
- 역할: 한국어·영어 문장을 allowlist typed intent와 parameter로 변환
- Action: redaction된 자연어에서 intent와 parameter 추출
- Goal: `Produce a structured natural-language command interpretation draft`
- Skill: `natural-language-intent-parsing`, `evidence-redaction`
- Agent 직접 tool: 없음
- 실행 tool: `ConfirmedCommandDispatchPort`가 기존 typed use case로만 위임
- 금지: 자연어 agent의 직접 shell/Git/Bitbucket/Jenkins/Grafana 호출과 merge/deploy

### 챗봇 수정 방향의 실제 UI 경로

```text
분석 후보가 표시된 상태의 챗봇 문장
  → dashboard.js가 후보 번호와 수정 방향 식별
  → 해당 form의 patchInstruction에 원문 저장
  → POST /ui/analyses/{analysisId}/selections
  → 7절의 Draft PR workflow
```

입력이 없으면 기존 후보 기반 수정, 입력이 있으면 안전 정책 안에서 사용자 방향을 최대한 반영하는
동일 workflow를 사용한다.

## 11. Agent capability와 저장 요약

| Agent | Skill 수 | 직접 Tool 수 | 주 사용 기능 |
| --- | ---: | ---: | --- |
| `natural-language-command-agent` | 2 | 0 | 챗봇 해석 |
| `incident-analysis-agent` | 3 | 0 | Jenkins/관측 원인 분석 |
| `candidate-refinement-agent` | 3 | 0 | 정밀 AI 분석 |
| `patch-author-agent` | 2 | 0 | patch 생성 |
| `patch-review-agent` | 2 | 0 | patch 독립 검토 |

모든 agent는 skill과 tool 각각 5개 이하이며 `ActionRetryPolicy.FIRE_ONCE`를 사용한다. patch 수정
재시도는 Embabel 내부 자동 재시도가 아니라 `GuardedHotfixWorkflowAdapter`가 검증 실패를 근거로
제한된 횟수만 수행한다.

- 분석과 후보: `hotfix_agent.incident_analyses` 및 관계형 candidate/evidence 테이블
- hotfix: `hotfix_agent.incident_hotfixes`
- 사용자 수정 방향: nullable `incident_hotfixes.patch_instruction`
- 검증 단계: `incident_hotfix_verification_stages`
- CI 단계: `incident_hotfix_ci_stages`

분석, 정밀 분석, patch workflow는 background 상태를 DB에 기록한다. 서버 재기동 시 미완료 작업을
복구하며 저장된 수정 방향도 patch 작성 agent에 다시 전달한다. 수정 방향은 단일 값이므로 JSON
컬럼을 사용하지 않는다.
