# 에이전트 툴 및 권한 카탈로그

## 1. 원칙

Tool은 허용된 동작만 노출하는 deterministic adapter다. LLM에는 범용 HTTP client, 범용 shell 또는
임의 query 실행기를 제공하지 않는다. 입력은 typed request이며 adapter가 URL, query template,
command allowlist와 응답 크기 한도를 검증한다.

한 agent/subagent가 직접 호출할 수 있는 tool은 최대 5개다. 전역 tool registry 전체를 모든 agent에
노출하지 않고 capability manifest에 owner를 명시한다. 6번째 tool이 필요하면 별도 목적과 typed
input/output을 가진 하위 에이전트로 분리한다. 현재 소유권과 개수는
[에이전트 카탈로그](../agents/agent-catalog.md#6-agent별-허용-스킬과-툴)를 정본으로 사용한다.

## 2. Tool 목록

| Tool ID | Port/Adapter | 허용 동작 | 분류 | 사용 조건 |
| --- | --- | --- | --- | --- |
| `TOOL-JENKINS-READ` | `JenkinsQueryPort` / `JenkinsRestAdapter` | job/build metadata, console, test report `GET` | read | Jenkins 분석 또는 명시적 CI refresh |
| `TOOL-GRAFANA-METRIC` | `MetricQueryPort` / `GrafanaPrometheusAdapter` | allowlist Prometheus query | read | 유효한 observability scope |
| `TOOL-GRAFANA-TRACE` | `TraceQueryPort` / `GrafanaTempoAdapter` | bounded trace search/detail | read | trace 증거 필요 |
| `TOOL-GRAFANA-LOG` | `LogQueryPort` / `GrafanaLokiAdapter` | bounded `query_range` | read | log/trace correlation 필요 |
| `TOOL-BITBUCKET-READ` | `SourceRevisionPort` / `BitbucketRestAdapter` | repository, branch, open PR 조회 | read | source resolution/freshness |
| `TOOL-SOURCE-SEARCH` | `SourceRepositoryPort` / `LocalGitRepositoryAdapter` | 고정 commit fetch/read/`rg` 검색 | read | 분석 또는 선택 후 context 조회 |
| `TOOL-WORKTREE` | `PatchWorkspacePort` / `LocalGitPatchAdapter` | detached worktree 생성, patch 적용, diff 검사 | local write | 유효한 `SelectedCandidate` |
| `TOOL-VERIFY` | `VerificationPort` / `CiParityVerificationAdapter` | focused task와 고정 Jenkins 동등 profile 실행 | process | patch policy 통과 후 |
| `TOOL-GIT-PUBLISH` | `PullRequestPort` / Git publisher | `agent/hotfix/*` branch push | external write | verification/review/source freshness 통과 |
| `TOOL-BITBUCKET-PR` | `PullRequestPort` / `BitbucketRestAdapter` | Draft PR create/read | external write | branch publish 성공 후 |
| `TOOL-STATE` | `AnalysisStatePort` / `JsonFileAnalysisStateAdapter` | runtime JSON atomic read/write | local write | action 상태 전이 시 |

## 3. Tool별 제한

### 3.1 Jenkins

허용:

- `GET` job/build metadata
- `GET` console text
- `GET` test report

금지:

- build trigger/rebuild
- build cancel
- job/config/credential 변경

`JENKINS_TLS_VERIFY=false`는 시연용 Jenkins client에만 적용한다.

### 3.2 Grafana datasource proxy

Grafana read-only service account token과 다음 datasource UID를 사용한다.

| Datasource | UID | 한도 |
| --- | --- | --- |
| Loki | `P8E80F9AEF21F6940` | 500 lines, 2 MB |
| Prometheus | `prometheus` | template당 100 series |
| Tempo | `tempo` | search 20 traces, detail 3 traces |

scope는 `namespace=fms-eu-{env}`, `service_name=fms-eu-{env}-app`이다. 사용자는 raw query를 전달할 수
없고 tool이 registry의 query ID를 template으로 변환한다. `GRAFANA_TLS_VERIFY=false`는 Grafana client에만
적용하며 datasource별 별도 insecure client를 만들지 않는다.

Grafana dashboard, datasource, alert rule, contact point write는 금지한다.

### 3.3 Bitbucket

Read tool:

- `autocrypt/fms` repository metadata
- branch head
- open PR source/destination/commit
- 기존 hotfix branch와 PR 존재 여부

Write tool:

- `agent/hotfix/*` branch push
- reviewer가 없는 Draft PR create

approve, merge, tag, release와 source branch 직접 push는 금지한다.

### 3.4 Local Git과 source search

- 사용자의 `~/workspace/fms` working tree를 수정하지 않는다.
- 분석 단계는 고정 commit의 read/search만 허용한다.
- 선택 이후 `.agent/runtime/worktrees/{hotfixId}` detached worktree만 수정한다.
- 검색은 path와 result count를 제한한 `rg` wrapper를 사용한다.
- 범용 shell 문자열을 LLM output에서 실행하지 않는다.

### 3.5 CI verification

Tool input은 shell 문자열이 아니라 `FocusedVerificationTask` 또는 `JENKINS_PR_PARITY` profile ID다.
adapter가 module과 변경 유형을 allowlist task로 변환하며 LLM이 command를 조립하지 않는다.

| 변경 대상 | 최소 task |
| --- | --- |
| `eu-app` Java | module test, architecture test, Checkstyle |
| gateway | gateway test, 필수 architecture/Checkstyle |
| metrics | metrics test, 필수 architecture/Checkstyle |

migration이 필요한 변경은 tool을 실행하지 않고 사람 검토로 전환한다. retry는 최대 2회다.

focused task 성공은 PR 생성 조건이 아니다. Draft PR 직전에는 분석 source에 고정된
`eu/Jenkinsfile`의 배포 제외 pipeline과 동등한 `JENKINS_PR_PARITY`를 동일 patch commit에서 실행한다.
adapter는 Jenkinsfile SHA-256과 승인된 profile version의 registry를 검사하며 unknown hash를 실행
가능한 profile로 추측하지 않는다.

| Parity stage | 필수 실행 |
| --- | --- |
| Gradle verification | Jenkinsfile에 나열된 architecture, Checkstyle, test, integration/migration/external API, JaCoCo task 전체 |
| Test Coverage | `eu/ci/print-jacoco-coverage.sh`로 report 확인 |
| Image Build | app/gateway/metrics local Jib Docker image build |
| Integration Test | `eu/ci/run-integration-tests.sh`의 Compose health check와 Newman suite |

deploy, ECR push와 manifest update는 금지된 외부 변경이므로 실행하지 않는다. 필수 stage가 skip, 실패 또는
실행 불가이면 tool은 성공 결과를 만들지 않고 `NEEDS_HUMAN_REVIEW` 사유를 반환한다. 성공 결과에는
base/patch commit, Jenkinsfile SHA-256, profile version, 모든 stage/task exit code를 기록한다.

publisher는 다음 predicate가 참일 때만 호출할 수 있다.

```text
parity.status == SUCCESS
AND parity.patchCommit == currentWorktreeHead
AND parity.allRequiredStagesExecuted == true
```

### 3.6 Runtime state

- 위치: `.agent/runtime`
- Git 비추적
- schema version 필수
- 임시 파일 작성 후 atomic move
- token, 원본 secret과 불필요한 운영 evidence 저장 금지
- 재시작 복구 시 external write 전에 branch/PR 재조회

## 4. 권한 매트릭스

| 단계 | Jenkins | Grafana | Bitbucket read | Source read | Worktree | Gradle | Git push | Draft PR |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 분석 전 | - | - | source resolve | - | - | - | - | - |
| Jenkins 분석 | read | - | read | read | - | - | - | - |
| 관측 분석 | - | read | read | read | - | - | - | - |
| 후보 조회 | - | - | - | - | - | - | - | - |
| 선택 검증 | - | - | freshness read | read | - | - | - | - |
| patch | - | - | - | read | write | - | - | - |
| focused/parity verification/review | - | - | - | read | read | execute | - | - |
| publish | - | - | freshness/read | read | read | result read | write | create |
| CI refresh | read | - | PR read | - | - | - | - | - |

## 5. 명시적으로 만들지 않을 Tool

- arbitrary shell execution tool
- arbitrary HTTP request tool
- user/LLM supplied PromQL, LogQL, TraceQL executor
- Kubernetes write tool
- Jenkins trigger/config tool
- Bitbucket merge/approve/tag/release tool
- secret 조회·출력·회전 tool
- migration 생성 또는 적용 tool
- 자연어 원문 또는 LLM output을 바로 실행하는 command-dispatch tool

자연어 command 실행은 agent tool이 아니다. application service가 version/hash 확인과 typed schema
검증을 마친 뒤 기존 inbound use case를 호출한다. 따라서 자연어 기능을 추가해도 agent의 외부 권한은
증가하지 않는다.

## 6. Tool contract test

각 외부 adapter는 fixture 기반 contract test에서 다음을 검증한다.

1. 허용 endpoint와 method만 호출한다.
2. timeout, TLS 설정과 인증 header가 client별로 격리된다.
3. response limit 초과가 LLM 입력 전에 차단된다.
4. 오류와 observability log에 secret이 없다.
5. write tool은 `JENKINS_PR_PARITY` 전체 성공과 patch commit 일치 전 호출되지 않는다.
6. 실제 credential smoke test는 기본 `check`와 분리된다.
