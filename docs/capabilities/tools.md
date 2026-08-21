# 애플리케이션 Tool과 권한 카탈로그

## 1. 현재 경계

여기서 tool은 typed port를 구현하는 결정론적 adapter를 뜻한다. 현재 네 Embabel agent에는 external tool을
직접 노출하지 않는다. agent capability manifest의 tool 수는 모두 0이며, application service가 아래
port를 순서대로 호출한다.

범용 HTTP client, 범용 shell, LLM이 만든 query 또는 command는 실행하지 않는다.

## 2. 실제 Port와 Adapter

| ID | Port / Adapter | 허용 동작 | 분류 |
| --- | --- | --- | --- |
| `TOOL-JENKINS-EVIDENCE` | `JenkinsEvidencePort` / `JenkinsRestAdapter` | build metadata, console, test report 조회 | external read |
| `TOOL-OBS-EVIDENCE` | `ObservabilityEvidencePort` / `GrafanaObservabilityAdapter` | allowlist metric/trace/log/alert 조회 | external read |
| `TOOL-SOURCE-REVISION` | `SourceRevisionPort` / `BitbucketSourceRevisionAdapter` | branch, open PR, full commit 확인 | external read |
| `TOOL-SOURCE-CONTEXT` | `SourceContextPort` / `BitbucketSourceContextAdapter` | 고정 commit의 제한된 코드 문맥 조회 | external read |
| `TOOL-WORKSPACE` | `PatchWorkspacePort` / `LocalGitPatchWorkspaceAdapter` | 격리 worktree, patch, diff, commit, branch push | local/external write |
| `TOOL-VERIFY` | `VerificationPort` / `LocalJenkinsParityVerificationAdapter` | focused test와 고정 parity profile 실행 | local process |
| `TOOL-DRAFT-PR` | `PullRequestPort` / `BitbucketDraftPullRequestAdapter` | reviewer 없는 Draft PR 생성과 조회 | external write |
| `TOOL-STATE` | `IncidentStatePort` / `JsonIncidentStatePersistenceAdapter` | runtime JSON read/write | local write |

위 표는 한 agent에 8개 tool을 부여한다는 뜻이 아니다. 이들은 결정론적 workflow 전체의 adapter이며
agent 직접 할당은 0개다.

## 3. 외부 시스템 제한

### 3.1 Jenkins

허용: job/build metadata, console text, test report와 명시적 PR build 상태 `GET`.

금지: build trigger/rebuild/cancel, job/config/credential 변경. `JENKINS_TLS_VERIFY=false`는 시연용
Jenkins client에만 적용한다.

### 3.2 Grafana

Grafana read-only service account token과 설정된 Loki, Prometheus, Tempo datasource UID를 사용한다.
scope는 `namespace=fms-eu-{env}`, `service_name=fms-eu-{env}-app`으로 고정한다.

- Loki 결과 최대 500 row
- Tempo search 최대 20 trace, detail 최대 3 trace
- adapter 응답 body 최대 2,000,000자
- raw PromQL, LogQL, TraceQL 입력 금지
- dashboard, datasource, alert rule과 contact point write 금지

`GRAFANA_TLS_VERIFY=false`는 시연용 Grafana client에만 적용한다.

### 3.3 Bitbucket과 Git

- 저장소는 `autocrypt/fms`로 고정한다.
- 원격 branch head와 open PR source를 읽는다.
- write는 `agent/hotfix/*` branch push와 reviewer 없는 Draft PR만 허용한다.
- 원 source branch push, approve, merge, tag, release는 금지한다.
- Bitbucket PR 응답의 축약 commit은 commit API에서 full SHA로 해석한 뒤 parity commit과 비교한다.
- 사용자의 FMS working tree는 수정하지 않고 `.agent/runtime/worktrees/{hotfixId}`만 사용한다.

## 4. 검증 Tool 계약

LLM이 shell command를 만들지 않는다. adapter가 승인된 `JENKINS_PR_PARITY` profile을 실행한다.

| Stage | 현재 실행 내용 |
| --- | --- |
| `jenkins-gradle-verification` | Jenkinsfile과 대응하는 Gradle 검증 task |
| `jenkins-coverage-report` | JaCoCo report 확인 |
| `jenkins-image-build` | app/gateway/metrics local Jib image build |
| `jenkins-integration-test` | Docker Compose health와 Newman collection |

focused 검증은 patch 반복 피드백일 뿐 PR 조건이 아니다. 모든 required stage가 동일 patch commit에서
`exitCode=0`이고 worktree HEAD가 그 commit과 같아야 publish가 가능하다.

```text
parity.status == SUCCESS
AND parity.patchCommit == currentWorktreeHead
AND parity.allRequiredStagesExecuted == true
```

stage 누락·실패·실행 불가, unknown Jenkinsfile hash 또는 Docker/Newman 불가는 skip하지 않고
`NEEDS_HUMAN_REVIEW`로 끝낸다. deploy, ECR push와 manifest update는 실행하지 않는다. patch 재시도는
최대 2회, parity worker 기본 상한은 2개다.

## 5. Runtime state와 Docker 경로

- host 상태: `.agent/runtime`
- container 상태: `/opt/my-agent/.agent/runtime`
- FMS source mount: `/workspace/fms`
- Testcontainers host override: `host.docker.internal`
- Newman host workspace root: `AGENT_NEWMAN_WORKSPACE_ROOT`로 절대 경로 지정
- schema version, 임시 파일 후 atomic move, secret 비저장을 적용

## 6. 단계별 권한

| 단계 | 외부 read | local write/process | 외부 write |
| --- | --- | --- | --- |
| 분석 | Jenkins 또는 Grafana, Bitbucket source/context | state | 없음 |
| 후보 조회 | 없음 | state read | 없음 |
| 선택/patch | Bitbucket freshness/context | worktree, patch, focused test | 없음 |
| parity/review | source read | Gradle, Jib, Compose, Newman | 없음 |
| publish | Bitbucket freshness | 검증 결과 read | hotfix branch, Draft PR |
| CI refresh | Jenkins와 PR read | state | 없음 |

## 7. 만들지 않는 Tool

- arbitrary shell 또는 HTTP executor
- user/LLM supplied PromQL, LogQL, TraceQL executor
- Kubernetes write, Jenkins trigger/config
- Bitbucket merge/approve/tag/release
- secret 조회·출력·회전
- migration 생성·적용
- 자연어 원문이나 LLM output의 직접 command dispatch

자연어 실행은 version/hash 확인 뒤 기존 typed inbound use case로 위임하므로 agent 권한을 늘리지 않는다.
