# 에이전트 및 서브에이전트 카탈로그

## 1. 설계 원칙

HTTP controller와 application service가 실행 경계를 소유하고 Embabel agent는 typed artifact를 받아
계획과 추론을 수행한다. LLM이 임의 URL, query 또는 shell command를 만들고 직접 실행하게 하지 않는다.

MVP에서는 책임이 다른 실행 경계를 섞지 않기 위해 네 개의 top-level Embabel agent만 둔다.
표의 서브에이전트는 독립 prompt/model 경계가 필요한 전문 action 묶음이다. Embabel planner는
서브에이전트를 명령형으로 직접 호출하지 않고, 선행 action이 만든 typed artifact를 통해 다음 action을
선택한다.

모든 agent/subagent는 직접 할당 skill 최대 5개, tool 최대 5개를 지켜야 한다. 부모는 자식 capability를
상속하지 않으며 자식이 반환한 typed artifact만 소비한다.

관련 문서:

- [SRS](../requirements/SRS.md)
- [실행 조건](execution-conditions.md)
- [스킬 카탈로그](../capabilities/skills.md)
- [툴 카탈로그](../capabilities/tools.md)

## 2. Top-level 에이전트

| Agent | 사람이 이해하는 목적 | 목표 | 진입 artifact | 종료 artifact |
| --- | --- | --- | --- | --- |
| `IncidentAnalysisAgent` | 조사원을 배치해 장애 증거를 선택 가능한 원인 목록으로 만든다. | `CandidatesPrepared` 또는 `NeedsHumanReview` | `AnalysisRequestAccepted` | `AnalysisSession` + `BugCandidate[]` |
| `HotfixImplementationAgent` | 사용자가 고른 원인만 안전하게 수정하고 검증된 Draft PR로 만든다. | `DraftPullRequestCreated` 또는 `NeedsHumanReview` | `SelectedCandidate` | `HotfixResult` |
| `ResolutionAgent` | 실제 Jenkins 결과를 확인해 정말 해결됐는지 판정한다. | `IssueResolved` 또는 현재 CI 상태 유지 | `CiRefreshRequested` | `PullRequestBuildResult` |
| `NaturalLanguageCommandAgent` | 사용자 문장을 실행하지 않고 확인 가능한 typed command로 바꾼다. | `CommandReadyForConfirmation`, `NeedsClarification` 또는 `CommandRejected` | `NaturalLanguageRequestAccepted` | `CommandInterpretation` |

### 2.1 IncidentAnalysisAgent

장애 현장의 조사 책임자 역할이다. Jenkins 사건이면 build 조사원, Grafana 사건이면 관측 조사원을
선택하고, 조사 결과를 원인 분석 담당자에게 넘긴다. source revision을 고정한 뒤 후보를 만들며 분석
단계에서는 worktree, branch, patch와 PR을 만들 수 없다.

직접 매핑: `SRS-JEN-001~005`, `SRS-OBS-001~011`, `SRS-CAN-001~007`.

### 2.2 HotfixImplementationAgent

수정 작업의 책임자 역할이다. 사용자가 선택한 `SelectedCandidate`만 받아 작성자, 검토자, 검증자와
발행 담당자에게 순서대로 일을 넘긴다. policy gate, 독립 review와 Jenkins 동등 검증을 모두 통과해야
발행 담당자를 실행한다. 선택 artifact가 없으면 어떤 수정 작업도 시작할 수 없다.

직접 매핑: `SRS-SEL-001~006`, `SRS-GIT-001~007`, `SRS-VER-001~014`, `SRS-PR-001~005`.

### 2.3 ResolutionAgent

최종 확인 담당자 역할이다. 명시적인 CI refresh API 요청에만 Jenkins PR job 상태를 한 번 읽는다.
성공이 확인된 경우에만 `IssueResolved`를 만들며 scheduler나 polling action은 등록하지 않는다.

직접 매핑: `SRS-API-006`, `SRS-PR-006~007`.

### 2.4 NaturalLanguageCommandAgent

명령 접수 담당자 역할이다. 한국어 또는 영어 문장을 폐쇄된 intent와 typed parameter로 변환하지만
외부 tool과 기존 use case를 실행하지 않는다. 모호하거나 필수값이 없으면 질문을 만들고, 정책 우회나
지원하지 않는 작업은 거절한다. 실행은 application layer가 사용자의 version/hash 확인을 검증한 후
기존 구조화 use case에 위임한다. 따라서 이 에이전트에는 Jenkins, Grafana, Bitbucket, source, shell,
Git tool을 할당하지 않는다.

직접 매핑: `SRS-NL-001~014`.

## 3. 전문 서브에이전트

| Subagent | Parent | 사람이 이해하는 목적 | 모델 역할 | 입력 → 출력 |
| --- | --- | --- | --- | --- |
| `JenkinsTriageSubagent` | `IncidentAnalysisAgent` | 긴 build log에서 실제 실패와 코드 단서만 추린다. | triage | `BuildEvidence` → `TriageSummary` |
| `ObservabilityTriageSubagent` | `IncidentAnalysisAgent` | metric, trace, log를 시간 순서로 연결해 장애 흐름을 만든다. | triage | `ObservabilityEvidenceSet` → `TriageSummary` |
| `RootCauseSubagent` | `IncidentAnalysisAgent` | 증거가 다른 원인을 섞지 않고 선택 가능한 후보로 만든다. | reasoning | `TriageSummary` + `SourceContext` → `BugCandidate[]` |
| `PatchAuthorSubagent` | `HotfixImplementationAgent` | 선택된 원인만 해결하는 최소 patch를 제안한다. | reasoning | `SelectedCandidate` + `RepositoryContext` → `PatchProposal` |
| `PatchReviewSubagent` | `HotfixImplementationAgent` | 작성자가 놓친 회귀와 근거 불일치를 반대 관점에서 찾는다. | review | `AppliedPatch` + `VerificationResult` → `PatchReview` |
| `VerificationSubagent` | `HotfixImplementationAgent` | focused test와 Jenkins 동등 검증을 실행해 PR 발행 가능 여부를 증명한다. | deterministic | `AppliedPatch` + `VerificationPlan` → `VerificationResult` |
| `PullRequestPublicationSubagent` | `HotfixImplementationAgent` | 검증 결과를 사람이 읽을 문서로 만들고 승인된 commit만 Draft로 발행한다. | review | approved artifacts → `DraftPullRequest` |

## 4. 서브에이전트 분리 기준

skill 또는 tool 중 하나라도 6개째가 필요해지면 해당 기능을 기존 agent에 추가하지 않고 하위
에이전트로 분리한다. 분리된 에이전트도 각각 5개 제한을 동일하게 적용한다.

그 외에는 다음 중 하나에 해당할 때 별도 Embabel agent/action group으로 분리한다.

1. 다른 모델 역할을 사용해야 한다.
2. 입력·출력 DTO와 token budget이 독립적이다.
3. write 권한 또는 safety gate 경계가 달라진다.
4. fixture와 eval을 독립적으로 실행할 가치가 있다.

새 하위 에이전트는 목적 한 문장, typed input/output, capability manifest와 독립 fixture를 가져야 한다.
단순 parser, redaction과 policy 검사처럼 독립 판단 목적이 없는 기능은 개수 맞추기용 agent로 만들지
않고 deterministic service/tool로 구현한다.

## 5. 모델 배정

| 역할 | 기본 환경변수 | 사용 대상 | fallback |
| --- | --- | --- | --- |
| triage | `LITELLM_TRIAGE_MODEL` | Jenkins/관측 증거 축약과 분류 | `LITELLM_MODEL` |
| reasoning | `LITELLM_REASONING_MODEL` | 원인 후보와 patch 제안 | `LITELLM_MODEL` |
| review | `LITELLM_REVIEW_MODEL` | 독립 patch review와 문서 작성 | reasoning model |

초기 연결 검증은 하나의 모델로 수행할 수 있지만 DTO, prompt와 evaluation fixture는 역할별로 분리한다.
모델 변경은 tool 권한이나 patch 정책을 확대하지 않는다.

## 6. Agent별 허용 스킬과 툴

아래 개수는 직접 할당된 capability만 센다. `TOOL-STATE`처럼 여러 agent가 사용하는 공통 read/write
adapter도 각 agent의 직접 tool 개수에는 포함한다.

| Agent/Subagent | 직접 스킬 | 수 | 직접 툴 | 수 |
| --- | --- | ---: | --- | ---: |
| `IncidentAnalysisAgent` | 없음 | 0 | `TOOL-STATE` | 1 |
| `HotfixImplementationAgent` | 없음 | 0 | `TOOL-STATE` | 1 |
| `ResolutionAgent` | 없음 | 0 | `TOOL-JENKINS-READ`, `TOOL-BITBUCKET-READ`, `TOOL-STATE` | 3 |
| `NaturalLanguageCommandAgent` | `natural-language-intent-parsing`, `evidence-redaction` | 2 | `TOOL-STATE` | 1 |
| `JenkinsTriageSubagent` | `jenkins-failure-triage`, `evidence-redaction` | 2 | `TOOL-JENKINS-READ`, `TOOL-SOURCE-SEARCH`, `TOOL-STATE` | 3 |
| `ObservabilityTriageSubagent` | `observability-correlation`, `evidence-redaction` | 2 | `TOOL-GRAFANA-METRIC`, `TOOL-GRAFANA-TRACE`, `TOOL-GRAFANA-LOG`, `TOOL-STATE` | 4 |
| `RootCauseSubagent` | `root-cause-analysis`, `source-context-selection`, `evidence-redaction` | 3 | `TOOL-SOURCE-SEARCH`, `TOOL-STATE` | 2 |
| `PatchAuthorSubagent` | `minimal-patch-proposal`, `evidence-redaction` | 2 | `TOOL-SOURCE-SEARCH`, `TOOL-WORKTREE`, `TOOL-STATE` | 3 |
| `PatchReviewSubagent` | `independent-patch-review`, `evidence-redaction` | 2 | `TOOL-WORKTREE`, `TOOL-STATE` | 2 |
| `VerificationSubagent` | 없음 | 0 | `TOOL-WORKTREE`, `TOOL-VERIFY`, `TOOL-STATE` | 3 |
| `PullRequestPublicationSubagent` | `pull-request-documentation`, `evidence-redaction` | 2 | `TOOL-BITBUCKET-READ`, `TOOL-GIT-PUBLISH`, `TOOL-BITBUCKET-PR`, `TOOL-STATE` | 4 |

현재 최대값은 skill 3개, tool 4개로 모두 제한 이내다. architecture test는 manifest의 선언과 이 표가
달라지거나 5개를 초과하면 agent 이름과 초과 capability ID를 출력하며 실패해야 한다.

구현에서는 `agent-capabilities.json`이 실제 `@Agent` 이름별 직접 capability를 선언하고
`AgentCapabilityArchTest`가 등록 agent 집합과 manifest의 1:1 일치, 중복, skill/tool 각각 최대 5개를
검사한다. 현재 등록된 LLM agent는 외부 tool group이 모두 0개다. 표의 Jenkins/Grafana/Git/검증/PR
tool은 LLM에 노출하지 않고 application workflow의 typed port로 실행한다.

## 7. 구현 패키지 제안

```text
com.example.myagent
├── agent
│   ├── analysis
│   ├── hotfix
│   ├── command
│   └── resolution
├── command
├── incident
├── jenkins
├── observability
├── repository
├── verification
└── pullrequest
```

각 vertical slice는 port를 통해서만 연결하며 controller-facing application service는 상위 흐름만
나열한다. 실제 package는 [시스템 아키텍처](../design/system-architecture.md)의 규칙을 따른다.
