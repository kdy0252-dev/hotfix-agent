# Jenkins·Grafana FMS 핫픽스 에이전트 상세 설계

## 1. 설계 기준

이 문서는 2026-08-21 현재 구현을 설명한다. 시스템은 개발자 Mac의 Docker 또는 로컬 JVM에서 실행되고,
사용자가 API로 지정한 Jenkins 실패나 Grafana 관측 범위만 분석한다. 사용자가 후보를 선택한 뒤에만
격리 worktree를 만들고, Jenkins 동등 검증을 모두 통과한 commit만 Bitbucket Draft PR로 발행한다.

고정 정책:

- 저장소 `autocrypt/fms`, 서비스 `EU_APP`
- source는 원격 branch 또는 open PR
- hotfix branch `agent/hotfix/*`
- 최대 10 files, 500 added+deleted lines, patch 재시도 2회
- migration, secret, `.env*`, key/certificate, `Jenkinsfile`, Kubernetes·Helm·배포 manifest 변경 금지
- reviewer 없는 Draft PR만 허용
- merge, approve, tag, release, deploy와 Kubernetes write 금지
- 운영 담당 표기는 `BE팀`
- Jenkins와 Grafana만 시연 환경에서 TLS 검증 비활성화 가능

## 2. 핵심 결정

### 2.1 자동 polling을 하지 않는다

Jenkins와 Grafana 분석은 명시적 HTTP 요청으로만 시작한다. CI 상태도
`POST /api/v1/hotfixes/{hotfixId}/ci-status-refresh` 호출 시 한 번만 읽는다. scheduler, Jenkins trigger와
background polling은 없다.

### 2.2 분석과 변경을 분리한다

```text
analysis request
  → source revision 고정
  → bounded evidence와 source context 수집
  → IncidentAnalysisAgent
  → versioned BugCandidate 목록
  → 사용자 candidate ID + analysis version 선택
  → guarded hotfix workflow
```

선택 전에는 worktree, patch, branch와 PR을 만들지 않는다. 선택 시 source를 다시 읽어 고정 commit이
이동했으면 `409 Conflict`로 재분석을 요구한다.

### 2.3 Embabel은 판단, Java는 권한을 담당한다

Embabel agent는 자연어 해석, 후보 생성, patch 제안과 review만 수행한다. HTTP, Git, Gradle, Docker,
Newman과 Bitbucket write는 typed port를 구현한 결정론적 adapter가 수행한다. LLM에는 범용 shell,
HTTP client와 external tool group을 제공하지 않는다.

## 3. 현재 구조

```text
HTTP adapter
  → application use case/service
  → domain model
  → outbound port
  → deterministic adapter
                     ↘ Embabel model adapter (판단만)

package root
  ├─ incident      분석, 후보, 선택, patch/review와 hotfix workflow
  ├─ command       자연어 interpretation/confirmation
  ├─ orchestrator  비동기 실행 경계
  └─ global        설정, redaction, prompt budget와 공통 예외
```

각 기능은 가능한 범위에서 `adapter/in`, `adapter/out`, `application/port`, `application/domain` 경계를
유지한다. 실제 패키지와 전체 흐름은 [시스템 아키텍처](system-architecture.md)를 정본으로 한다.

## 4. 실제 Embabel agent

| Agent | 목적 | 입력 | 출력 | Tool 수 |
| --- | --- | --- | --- | ---: |
| `NaturalLanguageCommandAgent` | 문장을 폐쇄 intent와 typed parameter로 해석 | redacted text, schema | `CommandInterpretationDraft` | 0 |
| `IncidentAnalysisAgent` | 제한된 증거와 source에서 독립 원인 후보 생성 | evidence, source context | `List<BugCandidate>` | 0 |
| `PatchAuthorAgent` | 선택된 후보만 해결하는 최소 patch 제안 | selected candidate, context, policy | `PatchProposal` | 0 |
| `PatchReviewAgent` | diff의 회귀와 근거·정책 불일치 검토 | candidate, diff, verification | `PatchReview` | 0 |

모두 `ActionRetryPolicy.FIRE_ONCE`를 사용한다. 과거 설계에 있던 `HotfixImplementationAgent`,
`ResolutionAgent`, `*Subagent`와 여러 agent를 감싸는 wrapper bean은 구현하지 않았다. 이 책임은
application service와 workflow adapter로 분리했다.

agent별 skill/tool은 각각 최대 5개이며 `AgentCapabilityArchTest`가 검증한다. 현재 직접 skill은 최대
3개, external tool은 모두 0개다. 자세한 할당은 [에이전트 카탈로그](../agents/agent-catalog.md)와
[스킬 카탈로그](../capabilities/skills.md)를 따른다.

## 5. API 흐름

### 5.1 Jenkins

입력은 `jobPath`, `buildNumber`, branch 또는 PR source다. `JenkinsRestAdapter`가 `FMS-EU` 아래의
metadata, console과 test report만 GET하고 성공 build, 미완료 build와 revision 불일치를 거부한다.
로그는 크기를 제한하고 redaction한 뒤 agent에 전달한다.

### 5.2 Grafana

입력은 `startAt`, `endAt`, `environment`, source다. 범위는 최대 60분이고 환경은 `DEV`, `QA`,
`PROD`다. server가 다음 scope를 고정한다.

```text
namespace = fms-eu-{env}
service_name = fms-eu-{env}-app
```

`GrafanaObservabilityAdapter` 하나가 datasource proxy를 통해 Prometheus, Tempo, Loki와 alert evidence를
읽는다. raw query와 service/namespace는 API로 받지 않는다. Loki 최대 500 row, Tempo search 20/detail
3, HTTP body 2,000,000자 제한을 적용한다.

### 5.3 자연어

```text
POST interpretation
  → NaturalLanguageCommandAgent
  → deterministic schema/policy validation
  → version + command hash preview (외부 I/O 없음)

POST execution
  → TTL/version/hash/idempotency 확인
  → 기존 typed use case
```

지원 intent는 분석 2개, 후보 조회·선택, hotfix 조회와 CI refresh 여섯 개다. 자연어는 기존 권한을
확장하지 않으며 “알아서 고쳐줘”처럼 candidate 식별자가 없는 요청은 실행하지 않는다.

상세 HTTP 계약은 [API 문서](../api/hotfix-agent-api.md), 위협 모델은
[자연어 가드레일](natural-language-api-guardrails.md)을 따른다.

## 6. Hotfix workflow

`HotfixSelectionService`가 version, TTL, eligibility, evidence, idempotency, source freshness와
`AGENT_MODE=DRAFT_PR`을 검사한 뒤 background workflow를 접수한다.

```text
SelectedCandidate
  → 격리 worktree 생성
  → PatchAuthorAgent
  → 제안 경로 사전 policy
  → patch 적용
  → 실제 diff 사후 policy
  → focused verification
  → PatchReviewAgent
  → JENKINS_PR_PARITY
  → parity commit == current HEAD
  → source freshness
  → branch push
  → reviewer 없는 Draft PR create/read-back
```

patch/focused 검증은 최대 2회 반복한다. 동일 실패 근거로 같은 patch를 무한 생성하지 않는다. policy,
review 또는 검증 실패는 `NEEDS_HUMAN_REVIEW`로 끝나며 external write가 없다.

현재 workflow 상태 record는 다음 enum만 사용한다.

```text
Analysis: ANALYSIS_REQUESTED, ANALYZING, CANDIDATES_READY,
          NEEDS_HUMAN_REVIEW, FAILED
Hotfix:   SELECTED, PATCHING, VERIFYING, NEEDS_HUMAN_REVIEW,
          DRAFT_PR_CREATED, RESOLVED, FAILED
```

background workflow는 terminal 결과를 원자적으로 저장한다. 조회자가 모든 내부 단계의 중간 상태가
실시간으로 보존된다고 가정하면 안 된다.

## 7. Jenkins parity profile

focused test 성공만으로 PR을 발행하지 않는다. 고정 source의 `eu/Jenkinsfile` hash와 승인 profile을
결합해 같은 patch commit에서 다음 네 stage를 전부 실행한다.

| Stage | 필수 내용 |
| --- | --- |
| `jenkins-gradle-verification` | Jenkinsfile 대응 Gradle 검증 task |
| `jenkins-coverage-report` | JaCoCo report 확인 |
| `jenkins-image-build` | app/gateway/metrics local Jib image build |
| `jenkins-integration-test` | Docker Compose health와 Newman suite |

stage가 하나라도 실패·누락·실행 불가하면 PR을 생성하지 않는다. 성공 결과에는 base/patch commit,
Jenkinsfile path/hash, profile version과 각 exit code를 저장한다. parity 뒤 HEAD가 바뀌면 결과는
무효다. worker 기본 상한은 2개다.

## 8. Port와 Adapter

| Port | 현재 Adapter | 책임 |
| --- | --- | --- |
| `JenkinsEvidencePort` | `JenkinsRestAdapter` | 실패 build와 PR CI read |
| `ObservabilityEvidencePort` | `GrafanaObservabilityAdapter` | metric/trace/log/alert read |
| `SourceRevisionPort` | `BitbucketSourceRevisionAdapter` | branch/PR full revision과 freshness |
| `SourceContextPort` | `BitbucketSourceContextAdapter` | 고정 commit의 bounded code context |
| `PatchWorkspacePort` | `LocalGitPatchWorkspaceAdapter` | worktree, patch, diff, commit, push |
| `VerificationPort` | `LocalJenkinsParityVerificationAdapter` | focused/parity/Jib/Compose/Newman |
| `PullRequestPort` | `BitbucketDraftPullRequestAdapter` | Draft PR create/read-back |
| `IncidentStatePort` | `JsonIncidentStatePersistenceAdapter` | schema-versioned atomic JSON |

application adapter 전체 목록과 권한은 [툴 카탈로그](../capabilities/tools.md)를 따른다.

## 9. 상태와 idempotency

상태는 `.agent/runtime`에 schema version이 있는 JSON으로 저장하고 임시 파일 후 atomic move한다.
secret과 원본 운영 evidence는 저장하지 않는다. 동일 endpoint/key/body는 기존 resource를 반환하고 같은
key의 다른 body는 `409`다. 외부 write 전에는 branch/PR과 source를 다시 조회한다.

`DRAFT_PR_CREATED`는 해결 완료가 아니다. 사용자가 CI refresh를 호출하고 Jenkins 결과가 `SUCCESS`일
때만 `RESOLVED`가 된다.

## 10. 비용과 데이터 보호

기본 LLM budget:

| 역할 | 입력 | 출력 |
| --- | ---: | ---: |
| triage | 8,000 | 1,500 |
| reasoning | 16,000 | 4,000 |
| review | 8,000 | 1,500 |

provider와 structured binding attempt는 각각 1회다. 저장소 전체나 console 전체를 prompt로 보내지
않는다. Authorization, Cookie, token, password, connection string과 식별정보를 redaction한다.
prompt/completion 본문 logging은 비활성화하고 token usage만 Prometheus에 노출한다.

## 11. Docker 실행 경계

- host FMS 저장소를 container `/workspace/fms`에 mount
- container runtime state `/opt/my-agent/.agent/runtime`
- Docker socket mount로 parity container 실행
- Testcontainers host override `host.docker.internal`
- `AGENT_NEWMAN_WORKSPACE_ROOT`로 host `.agent/runtime` 절대 경로 전달
- Compose 실행 모드는 `DRAFT_PR`

원본 FMS working tree는 수정하지 않는다. 상세 절차는 [개발 가이드](../guides/development.md)를 따른다.

## 12. 테스트 전략

- `./gradlew check :app:aiMockTest`: unit, contract, architecture, Checkstyle와 네 agent mock
- `./gradlew aiTest`: LiteLLM judge와 로컬 Docker Langfuse score
- 실제 credential shadow: 기본 test와 분리
- architecture: production의 Spring AI 직접 의존 금지, FQCN, Vavr Try, capability 최대 5
- workflow: policy/parity/commit gate 전 Draft PR port 0회

AI 평가는 해석과 후보/patch/review 품질을 측정하지만 write 권한을 결정하지 않는다.

## 13. 실환경 검증 기준선

2026-08-21에 다음 흐름을 실제로 확인했다.

1. FMS source PR #1292의 Jenkins 실패를 입력으로 분석했다.
2. hotfix commit `d57a84a470878933ef23f370a01b034052394653`을 생성했다.
3. Gradle, JaCoCo, 세 Jib image, Compose health를 포함한 네 parity stage가 모두 `exitCode=0`이었다.
4. Newman bootstrap과 본 collection 20/20이 통과했다. admin 11, driving 9다.
5. reviewer 없는 Bitbucket Draft PR #1295를 만들었다.
6. Jenkins `PR-1295` build #1 시작을 확인했다. 최종 SUCCESS는 이 문서에서 확정하지 않는다.

## 14. 제외 범위

- 자동 사건 탐지, scheduler와 polling
- merge, approve, tag, release, deploy와 rollback
- Grafana dashboard/alert rule/contact point write
- Kubernetes resource 변경과 Pod 재시작
- DB migration, 운영 데이터와 secret 변경
- Slack/Jira 자동 통지
- 자유 형식 shell, HTTP와 observability query 실행
- 여러 로컬 인스턴스의 분산 lock

DB migration은 없다. runtime JSON은 개발 중 schema가 호환되지 않을 때 사용자가 백업 후 초기화할 수
있지만, 애플리케이션이 FMS 저장소나 운영 데이터를 초기화하지 않는다.
