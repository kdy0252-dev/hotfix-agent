# Embabel 기반 운영 장애 핫픽스 에이전트 설계

관련 요구사항은 [SRS](../requirements/SRS.md), API 계약은
[Hotfix Agent API](../api/hotfix-agent-api.md)를 정본으로 사용한다.

## 1. 목적

로컬 Mac에서 실행되는 Embabel 에이전트가 API로 전달된 다음 두 종류의 사건을 분석하고, 원인 진단부터
검증된 Draft PR 생성까지 수행하도록 설계한다.

1. Jenkins `FMS-EU` 멀티브랜치 빌드 실패
2. 지정 시간 범위와 환경의 Grafana/Loki/Tempo/Prometheus에서 확인되는 `eu-app` 이상

관측 환경은 API 파라미터로 `dev`, `qa`, `prod`를 받지만 어느 환경에도 변경을 수행하지 않는다.
에이전트가 허용받은 쓰기 작업은 `autocrypt/fms`에 `agent/hotfix/*` 브랜치를 만들고 Draft PR을
생성하는 것뿐이다.

### 성공 기준

- 동일 사건을 중복 처리하지 않는다.
- 운영 데이터 조회는 항상 읽기 전용이며 시간 범위와 결과 크기가 제한된다.
- 증거에서 FMS 소스 코드 결함까지 연결할 수 없는 사건은 자동 수정하지 않는다.
- 변경 파일 10개, 변경량 500줄, 수정 재시도 2회를 강제한다.
- migration, secret, `Jenkinsfile`, 배포 manifest를 변경하지 않는다.
- 로컬의 집중 테스트와 Jenkins PR 빌드가 모두 성공해야 `IssueResolved`가 된다.
- PR은 Draft만 생성하며 merge, tag, release, deploy API는 구현하지 않는다.
- 운영 담당자는 `BE팀`, PR reviewer는 비워 둔다.

## 2. 전제와 현재 확인 결과

| 항목 | 확인 결과 | 설계 반영 |
| --- | --- | --- |
| 실행 위치 | Kubernetes Pod가 아닌 개발자 Mac | 외부 시스템은 HTTPS API로 호출하고 로컬 상태 저장소를 사용한다. |
| Kubernetes context | `fms-prod` | 운영 관측 데이터는 읽기 전용으로만 접근한다. |
| EKS 조회 | `fms-prod`의 `monitoring` namespace에서 실제 관측 Service와 Grafana Ingress 확인 | 로컬에서는 외부 Grafana를 사용하고 내부 데이터 소스는 Grafana proxy를 우선 사용한다. |
| Jenkins | `https://jenkins.autocrypt-fms.io`, root job `FMS-EU` | API 요청으로 전달된 job/build만 조회한다. 시연 환경에서는 `tls-verify=false`를 허용한다. |
| Bitbucket | `autocrypt/fms`, 요청별 branch 또는 open PR | 선택한 source 기준으로 hotfix branch와 Draft PR을 만든다. |
| FMS 빌드 | app/gateway/metrics의 architecture, Checkstyle, test, integration test 수행 | 실패 stage와 module을 분석하여 최소 검증 명령을 고른다. |
| FMS 관측성 | Prometheus, Tempo, `X-Trace-Id` 연계가 소스에 존재 | trace ID를 로그와 소스 위치를 잇는 우선 correlation key로 사용한다. |
| LLM gateway | LiteLLM OpenAI 호환 endpoint 사용 가능 | 역할별 모델을 설정하되 초기에는 하나의 기본 모델로 시작할 수 있다. |
| Embabel | 프로젝트에 이미 적용, Spring AI 직접 의존 금지 architecture test 존재 | LLM 호출은 Embabel 모델 API를 통해서만 수행한다. |

### 2.1 확인된 prod 관측 토폴로지

| Component | Kubernetes Service | Port | 외부 접근 |
| --- | --- | --- | --- |
| Grafana | `monitoring/grafana` | 80 | `https://prod-grafana.autocrypt-fms.io` |
| Prometheus | `monitoring/kube-prometheus-stack-prometheus` | 9090 | ClusterIP, Grafana proxy 사용 |
| Alertmanager | `monitoring/kube-prometheus-stack-alertmanager` | 9093 | ClusterIP, Grafana Alerting API 사용 |
| Loki | `monitoring/loki-gateway` | 80 | ClusterIP, Grafana proxy 사용 |
| Tempo | `monitoring/tempo-query-frontend` | 3200 | ClusterIP, Grafana proxy 사용 |

Grafana datasource provisioning에서 다음 값도 확인했다.

- Prometheus UID: `prometheus`
- Tempo UID: `tempo`
- Loki UID: `P8E80F9AEF21F6940`

세 datasource의 metadata API에서 다음 공통 범위를 확인했다.

- namespace: `fms-eu-dev`, `fms-eu-qa`, `fms-eu-prod`
- application: `service_name=fms-eu-{env}-app`

Grafana는 `12.3.1`이며 `/api/health` 응답의 database 상태가 `ok`임을 확인했다. 외부 TLS 체인에
self-signed 인증서가 포함되어 있으므로 시연 환경에서는 `GRAFANA_TLS_VERIFY=false`를 명시적으로
사용한다.

## 3. 설계 원칙

### 3.1 관측과 변경을 분리한다

사건 탐지와 증거 수집은 항상 실행할 수 있지만 코드 변경은 별도 정책 gate를 통과해야 한다.
초기 배포는 `REPORT_ONLY` 모드로 실행하고, 실제 운영 사건에 대한 진단 정확도를 확인한 뒤
`DRAFT_PR` 모드를 활성화한다.

### 3.2 Grafana는 시간 범위 기반 datasource 탐색 진입점으로 사용한다

Grafana 화면을 스크래핑하지 않는다. API로 받은 탐색 시간 범위와 환경을 기준으로 Grafana datasource
proxy를 통해 각 데이터 소스의 읽기 API를 조회한다. 탐색 대상 서비스는 항상 `eu-app`이다.

- Loki: `GET /loki/api/v1/query_range`
- Tempo: `GET /api/search`, 선택된 trace의 `GET /api/v2/traces/{traceId}`
- Prometheus: `GET /api/v1/query`, `GET /api/v1/query_range`

공식 규격은 [Loki HTTP API](https://grafana.com/docs/loki/latest/reference/loki-http-api/),
[Tempo HTTP API](https://grafana.com/docs/tempo/latest/api_docs/),
[Prometheus HTTP API](https://prometheus.io/docs/prometheus/latest/querying/api/)를 기준으로 한다.

### 3.3 LLM이 외부 시스템을 직접 조작하지 않는다

LLM은 분류, 요약, 원인 가설, 패치 제안에만 사용한다. 다음 작업은 결정론적 Java adapter가 수행한다.

- Jenkins/Loki/Tempo/Prometheus/Bitbucket HTTP 요청
- Git branch와 worktree 생성
- 허용 경로, 변경량, 변경 파일 수 검사
- 테스트 명령 실행
- Draft PR 생성

PromQL, LogQL, TraceQL 전체 문자열을 LLM이 자유 생성하지 않는다. LLM은 허용된
`EvidenceQueryTemplate`과 파라미터만 선택하며 adapter가 label allowlist, 시간 범위와 limit를 검증해
최종 쿼리를 만든다.

### 3.4 Embabel은 고정 파이프라인이 아니라 타입 기반 계획에 사용한다

Jenkins 컴파일 실패에는 관측 로그가 불필요하고, 지정 시각의 HTTP 5xx 신호에는 Tempo와 Loki가
유용하다.
따라서 모든 사건에 같은 단계를 강제하지 않고, 각 `@Action`의 입력/출력 타입으로 필요한 경로만
Embabel planner가 선택하게 한다. Embabel의 action, goal, condition, 재계획 개념은
[공식 프로젝트 설명](https://github.com/embabel/embabel-agent)을 기준으로 한다.

### 3.5 자동 수정 불가 사건을 정상적인 종료 상태로 취급한다

`NeedsHumanReview`는 실패가 아니라 안전한 종료 결과다. 단, `IssueResolved`와 동일한 성공으로
집계해서는 안 된다.

자동 수정하지 않는 대표 사례:

- 인프라 용량, Kubernetes, 네트워크, 인증서, IAM 문제
- 데이터 정합성 또는 수동 데이터 복구가 필요한 문제
- DB schema/migration 변경이 필요한 문제
- secret 또는 배포 manifest 변경이 필요한 문제
- 원인이 둘 이상이며 단일 코드 수정으로 검증할 수 없는 문제
- 운영 증거에 개인정보나 secret 노출이 의심되는 문제
- 허용 변경량을 넘거나 재시도 2회를 소진한 문제

### 3.6 Draft PR 전에 Jenkins 동등 검증을 완료한다

focused test는 수정 반복을 빠르게 하기 위한 피드백일 뿐 PR 발급 조건이 아니다. Draft PR 직전에는
고정된 `eu/Jenkinsfile`의 배포 제외 검증 단계 전체를 수정된 동일 commit에서 실행한다. 전체 성공을
증명하지 못하면 Draft PR 대신 `NeedsHumanReview`로 종료한다.

## 4. 전체 아키텍처

```text
Client / Demo UI
      |
      | POST structured analysis request
      | OR natural-language interpretation + explicit confirmation
      v
Hotfix REST API --> Embabel Analysis Agent --> BugCandidate[]
      ^                                          |
      | GET candidate list                       | read-only evidence
      |                                          v
      +---------------- Jenkins / Grafana datasource proxy
      |
      | POST selected candidate + analysis version
      v
Embabel Hotfix Agent --> Local FMS Worktree --> focused tests --> independent review
      |                                                   |
      |                                                   v
      +<-- Jenkins parity verification <------------------+
      |
      +--> Bitbucket Draft PR --> explicit CI status refresh
```

### 4.1 모듈 경계

패키지는 기능 단위 vertical slice로 나누고, 각 slice 내부에서 `application`, `domain`, `adapter`를
구분한다.

| Slice | 책임 | 주요 외부 의존성 |
| --- | --- | --- |
| `incident` | 분석 요청, 후보 목록, 선택 상태와 Embabel 실행 시작 | 로컬 상태 저장소 |
| `hotfixapi` | 분석/목록/선택/상태 REST API | Spring MVC |
| `command` | 자연어 해석, 명확화, version/hash 확인과 기존 use case 위임 | Embabel model API, 로컬 상태 저장소 |
| `jenkins` | multibranch 실패 탐지, metadata/log/test report 수집 | Jenkins REST API |
| `observability` | 시간 범위/환경 정규화, `eu-app` 증거 계획과 데이터 조회 | Grafana datasource proxy |
| `repository` | FMS clone/worktree, source 검색, patch 적용, diff 정책 | local Git, filesystem |
| `verification` | module별 Gradle 검증과 결과 구조화 | FMS Gradle wrapper |
| `pullrequest` | branch push, Draft PR, PR build 상태 연결 | Bitbucket REST API |
| `hotfixagent` | Embabel actions, goals, LLM 기반 추론 | Embabel model API |

외부 slice가 다른 slice의 adapter나 service 구현을 직접 참조하지 않도록 port 계약만 노출한다.

### 4.2 사람이 이해하는 에이전트 역할

에이전트는 “할 수 있는 기술”이 아니라 “완료해야 하는 한 가지 목적”으로 나눈다.

| Agent | 쉽게 말하면 | 시작하는 때 | 해야 하는 일 | 멈추는 때 |
| --- | --- | --- | --- | --- |
| `IncidentAnalysisAgent` | 장애 조사 책임자 | Jenkins 또는 Grafana 분석 API가 들어왔을 때 | 적절한 조사원을 선택하고 증거와 원인 후보를 한 목록으로 정리 | 후보 목록 또는 사람 확인이 필요한 사유가 준비됨 |
| `HotfixImplementationAgent` | 수정 작업 책임자 | 사용자가 후보 하나를 명시적으로 선택했을 때 | 최소 수정, 검토, Jenkins 동등 검증과 안전한 Draft PR 발급을 순서대로 통제 | Draft PR이 만들어지거나 안전 조건 때문에 중단됨 |
| `ResolutionAgent` | 최종 확인 담당자 | 사용자가 CI 상태 갱신을 요청했을 때 | 실제 Jenkins PR build 결과를 한 번 확인 | 성공이면 해결 완료, 아니면 현재 상태 유지 |
| `NaturalLanguageCommandAgent` | 명령 접수 담당자 | 자연어 interpretation API가 들어왔을 때 | 문장을 폐쇄된 intent와 typed parameter 또는 명확화 질문으로 변환 | 확인 가능한 미리보기 또는 거절 사유가 준비됨 |

전문 하위 에이전트의 목적은 다음과 같다.

| Subagent | 사람이 기대하는 결과 |
| --- | --- |
| `JenkinsTriageSubagent` | 긴 build log에서 실제 실패 지점과 관련 코드 단서를 추린다. |
| `ObservabilityTriageSubagent` | 같은 시간대의 metric, trace와 log가 가리키는 장애 흐름을 연결한다. |
| `RootCauseSubagent` | 증거가 다른 원인들을 섞지 않고 사용자가 선택할 후보로 만든다. |
| `PatchAuthorSubagent` | 선택된 원인만 해결하는 최소 코드 변경을 제안한다. |
| `PatchReviewSubagent` | 작성자 관점에서 놓친 회귀, 정책 위반과 근거 불일치를 반대 입장에서 찾는다. |
| `VerificationSubagent` | focused test와 Jenkins 동등 검증을 실행하고 통과 여부를 증명한다. |
| `PullRequestPublicationSubagent` | 검증 결과를 사람이 읽을 PR 문서로 만들고 승인된 commit만 Draft로 발행한다. |

### 4.3 스킬·툴 최대 5개 규칙

각 agent/subagent에 직접 할당하는 skill 수와 tool 수는 각각 최대 5개다. capability manifest에서 ID를
명시하고 architecture test로 개수를 검사한다.

```text
directSkillCount <= 5
AND directToolCount <= 5
```

6번째 capability가 필요하면 기존 agent에 추가하지 않고 다음 기준으로 하위 에이전트를 만든다.

1. 새 목적을 한 문장으로 설명할 수 있다.
2. 부모에게서 받는 typed input과 돌려주는 typed output이 있다.
3. tool 권한의 소유자가 하나로 명확하다.
4. 독립 fixture로 성공과 실패를 검증할 수 있다.

부모는 하위 에이전트의 skill/tool을 상속하거나 우회 호출하지 않는다. 부모가 받는 것은 typed artifact뿐이므로
하위 에이전트를 추가해도 부모 권한이 넓어지지 않는다. 단순히 개수를 맞추기 위한 의미 없는 분리는 금지한다.

## 5. API 기반 분석과 선택 방식

에이전트는 Jenkins job이나 Grafana를 자동 polling하지 않는다. 호출자가 분석할 대상을 API로
전달한 경우에만 외부 시스템을 조회하고 Embabel 분석을 실행한다.

자연어 요청도 같은 원칙을 적용한다. 첫 요청은 해석과 미리보기만 만들고 외부 시스템을 조회하지
않는다. 사용자가 interpretation version과 command hash를 확인하는 두 번째 요청을 보낸 경우에만
typed command를 기존 구조화 use case에 위임한다. 자연어 agent는 external tool을 갖지 않으며
[자연어 API 가드레일](natural-language-api-guardrails.md)의 폐쇄 intent와 정책 overlay를 따른다.

### 5.1 Jenkins 분석 요청

호출자는 job 이름과 build number를 전달한다. console log 전체를 request body로 보내지 않고 참조만
전달하는 것을 기본으로 한다.

```http
POST /api/v1/analyses/jenkins
Idempotency-Key: demo-jenkins-main-181
Content-Type: application/json

{
  "jobPath": "FMS-EU/job/main",
  "buildNumber": 181,
  "source": {
    "type": "BRANCH",
    "branchName": "main"
  }
}
```

서버는 허용된 `FMS-EU` 하위 job인지 검증한 후 Jenkins API에서 metadata, console과 test report를
읽는다. `source`는 `BRANCH` 또는 `PULL_REQUEST` 중 하나여야 하며 Jenkins build revision과 선택한
source revision이 일치해야 한다. 에이전트가 생성한 `PR-*` job은 hotfix CI 검증에서도 조회할 수 있다.

PR을 기준으로 분석할 때는 다음과 같이 요청한다.

```json
{
  "jobPath": "FMS-EU/job/PR-1285",
  "buildNumber": 5,
  "source": {
    "type": "PULL_REQUEST",
    "pullRequestId": 1285
  }
}
```

### 5.2 Grafana 관측 탐색 요청

호출자는 탐색 시작/종료 시각, 환경, 수정 기준 source만 전달한다. service, namespace와 query 문자열은
request에서 받지 않는다.

```http
POST /api/v1/analyses/observability
Idempotency-Key: demo-observability-prod-20260820T125000-131000
Content-Type: application/json

{
  "startAt": "2026-08-20T12:50:00+09:00",
  "endAt": "2026-08-20T13:10:00+09:00",
  "environment": "PROD",
  "source": {
    "type": "PULL_REQUEST",
    "pullRequestId": 1285
  }
}
```

`environment`는 `DEV`, `QA`, `PROD`만 허용한다. 서버가 이를 각각 `fms-eu-dev`, `fms-eu-qa`,
`fms-eu-prod` namespace와 실제 datasource label로 변환한다. `startAt`은 `endAt`보다 앞서야 하며
범위는 최대 60분까지 허용한다. 해당 범위에서 `eu-app`만 조회한다. 관측 장애 요청에도 수정 기준이
될 `source`를 `BRANCH` 또는 `PULL_REQUEST`로 반드시 전달한다.

요청에 `service`, `namespace`, `promql`, `logql`, `traceql`, `observedAt`, `windowMinutes` 같은 미지원
필드가 포함되면 무시하지 않고 `400 Bad Request`로 거부한다. 시간 범위는 request에서 받고 query
template은 서버 정책만 결정한다.

### 5.3 후보 목록과 명시적 선택

분석 결과는 원인 가설을 합치지 않은 `BugCandidate` 목록으로 저장한다.

```http
GET /api/v1/analyses/{analysisId}
GET /api/v1/analyses/{analysisId}/candidates
POST /api/v1/analyses/{analysisId}/selections
Idempotency-Key: select-<analysisId>-<candidateId>
Content-Type: application/json

{
  "candidateId": "...",
  "analysisVersion": 1
}
```

선택 API가 호출되기 전에는 worktree, branch, patch 또는 PR을 만들지 않는다. 선택 시 다음을 다시
검증한다.

1. candidate가 해당 analysis에 포함되어 있다.
2. `analysisVersion`이 현재 버전과 같다.
3. candidate가 `ELIGIBLE`이며 충분한 evidence를 가진다.
4. base commit과 외부 build/관측 scope가 아직 유효하다.
5. 동일 candidate에 진행 중이거나 완료된 hotfix가 없다.

선택이 승인되면 `202 Accepted`와 `hotfixId`를 반환하고 patch·검증·Draft PR 작업을 시작한다.
`GET /api/v1/hotfixes/{hotfixId}`로 진행 상태와 최종 Draft PR URL을 조회한다.

### 5.4 중복 방지와 재시작 복구

MVP는 `.agent/runtime/incidents/{incidentId}.json`에 사건 상태를 저장한다. 파일은 Git에 포함하지 않고,
임시 파일 작성 후 atomic move로 교체한다. 단일 로컬 프로세스만 실행한다.

상태 전이:

```text
ANALYSIS_REQUESTED -> COLLECTING -> CANDIDATES_READY
                  -> NO_ACTIONABLE_CANDIDATE
                  -> NEEDS_HUMAN_REVIEW

CANDIDATES_READY -> SELECTED -> PATCHING -> FOCUSED_VERIFYING -> REVIEWING
REVIEWING -> JENKINS_PARITY_VERIFYING -> DRAFT_PR_CREATED
SELECTED | PATCHING | FOCUSED_VERIFYING | REVIEWING | JENKINS_PARITY_VERIFYING
  -> NEEDS_HUMAN_REVIEW

DRAFT_PR_CREATED -> CI_STATUS_REFRESHED -> RESOLVED
```

프로세스 재시작 시 미완료 분석과 선택된 hotfix를 재개하되 외부 write 전에 branch와 PR 존재 여부를
다시 확인한다. 자동 schedule은 없으며 재개 대상은 API로 시작된 작업뿐이다.

## 6. 증거 수집과 상관관계

### 6.1 Jenkins 경로

```text
BuildFailure
  -> stage/module/test 식별
  -> 실패 구간만 추출
  -> commit과 source tree 고정
  -> source 위치 검색
  -> 원인 진단
```

console 전체를 LLM에 전달하지 않는다. deterministic parser가 다음 부분만 추출한다.

- 실패 stage 이름
- 첫 번째 원인 예외와 연쇄 `Caused by`
- 실패 test class/method와 report 경로
- compiler/checkstyle/architecture violation 위치
- 마지막 실패 전후 제한된 줄 수

### 6.2 Grafana 관측 경로

```text
startAt + endAt + environment
  -> namespace mapping + eu-app 고정
  -> Prometheus로 증상과 영향 확인
  -> Tempo에서 error/slow trace 후보 조회
  -> trace ID 기준 Loki 로그 조회
  -> stack frame와 FMS source 연결
  -> 원인 진단
```

증거 수집 순서는 발견한 신호에 따라 바뀔 수 있다. Loki에서 trace ID가 먼저 발견되면 Tempo 상세
조회로 이어가고, JVM memory 신호처럼 trace가 의미 없는 경우 Tempo를 건너뛴다.

### 6.3 기본 안전 한도

| 항목 | 기본값 | 초과 시 처리 |
| --- | --- | --- |
| 관측 시간 범위 | request로 입력, 최대 60분 | 역전/빈 범위/60분 초과 거부 |
| Loki 결과 | 최대 500 lines, 응답 2 MB | 더 좁은 template로 1회 재조회 |
| Tempo 검색 | 최대 20 traces, 상세 조회 최대 3 traces | score 상위 trace만 사용 |
| Prometheus series | template당 최대 100 series | label 범위를 좁혀 1회 재조회 |
| Jenkins console | 원본 저장 가능, LLM 입력은 최대 200 관련 lines | parser가 축약 |
| LLM 증거 입력 | redaction 후 최대 토큰 budget | 중요도 순으로 축약 |

### 6.4 데이터 보호

- Authorization, Cookie, token, password, connection string 패턴을 수집 즉시 마스킹한다.
- request/response body는 기본 수집하지 않는다.
- tenant ID, 사용자 ID, 차량 식별자 등은 hash 또는 placeholder로 바꾼다.
- 원본 증거 파일은 `.agent/runtime/evidence`에 저장하고 PR에는 첨부하지 않는다.
- Embabel 관측 설정에서 prompt/result body 캡처를 끈다.
- Draft PR 본문에는 마스킹된 요약, query template ID, 시간 범위와 source link만 기록한다.

## 7. 도메인 모델 변경

작은 cohesive record를 사용한다. 긴 생성자나 placeholder가 늘어나면 목적별 VO로 분리하고
`$java-vo-parameter-design` 규칙에 따라 구현한다.

### 7.1 AnalysisRequest와 IncidentTrigger

Jenkins와 observability 입력을 하나의 거대한 nullable DTO로 합치지 않고 sealed interface의 서로 다른
구현으로 표현한다.

| Field | Change Type | Type | Type Class | Change Detail |
| --- | --- | --- | --- | --- |
| `analysisId` | Add | `AnalysisId` | Value Object | API 요청별 분석 식별자 |
| `idempotencyKey` | Add | `IdempotencyKey` | Value Object | 동일 요청의 중복 분석 방지 |
| `requestedAt` | Add | `Instant` | JDK type | API가 분석을 접수한 시각 |
| `environment` | Add | `Environment` | Enum | 관측 요청은 `DEV`, `QA`, `PROD` |

구현 타입:

| Type | 고유 필드 | 설명 |
| --- | --- | --- |
| `JenkinsFailureTrigger` | `JenkinsBuildRef`, `SourceRevision` | job, build number, URL, commit |
| `ObservabilityTrigger` | `ObservationWindow`, `Environment`, `SourceSelector` | 고정 `EU_APP` scope의 범위 기반 탐색 |

`ObservationWindow` 필드:

| Field | Change Type | Type | Type Class | Change Detail |
| --- | --- | --- | --- | --- |
| `startAt` | Add | `OffsetDateTime` | JDK type | 탐색 시작, `endAt`보다 이전 |
| `endAt` | Add | `OffsetDateTime` | JDK type | 탐색 종료, `startAt`보다 이후 |
| `duration` | Add | `Duration` | JDK type | 두 시각에서 계산하며 최대 60분 |

`SourceSelector`는 nullable field가 섞인 단일 record 대신 sealed interface로 구현한다.

| Type | 고유 필드 | base commit | Draft PR destination |
| --- | --- | --- | --- |
| `BranchSource` | `branchName` | 원격 branch 최신 commit | 동일 branch |
| `PullRequestSource` | `pullRequestId` | open PR source commit | 기존 PR source branch |

기존 PR source branch에 직접 push하지 않는다. 항상 별도의 `agent/hotfix/*` branch를 만들고 기존 PR
source branch를 destination으로 하는 Draft PR을 생성한다. merged/declined PR과 존재하지 않는 branch는
분석 단계에서 거부한다.

### 7.2 AnalysisSession과 BugCandidate

| Field | Change Type | Type | Type Class | Change Detail |
| --- | --- | --- | --- | --- |
| `analysisId` | Add | `AnalysisId` | VO | 후보 목록을 묶는 분석 식별자 |
| `version` | Add | `long` | primitive | stale selection 방지용 optimistic version |
| `sourceRevision` | Add | `SourceRevision` | VO | 선택한 branch 또는 PR source의 고정 commit |
| `pullRequestDestination` | Add | `BranchName` | VO | hotfix Draft PR이 향할 branch |
| `status` | Add | `AnalysisStatus` | Enum | `COLLECTING`, `CANDIDATES_READY`, 종료 상태 |
| `candidates` | Add | `List<BugCandidate>` | collection | 서로 구분되는 원인/수정 후보 목록 |
| `createdAt` | Add | `Instant` | JDK type | 분석 생성 시각 |
| `expiresAt` | Add | `Instant` | JDK type | 오래된 후보의 선택 차단 시각 |

`BugCandidate` 필드:

| Field | Change Type | Type | Type Class | Change Detail |
| --- | --- | --- | --- | --- |
| `candidateId` | Add | `CandidateId` | VO | analysis 내 안정적인 후보 식별자 |
| `title` | Add | `String` | JDK type | 목록에 표시할 짧은 제목 |
| `rootCause` | Add | `String` | JDK type | 증거로 지지되는 원인 가설 |
| `confidence` | Add | `Confidence` | VO | 점수와 근거를 함께 보유 |
| `sourceLocations` | Add | `List<SourceLocation>` | collection | 수정 가능성이 있는 위치 |
| `evidenceRefs` | Add | `List<EvidenceRef>` | collection | 마스킹된 증거 참조 |
| `counterEvidence` | Add | `List<String>` | collection | 후보에 반하는 증거 |
| `eligibility` | Add | `FixEligibility` | Enum | 선택 가능 여부와 human-only 구분 |
| `proposedFixSummary` | Add | `String` | JDK type | 아직 patch를 만들지 않은 수정 방향 |
| `verificationSummary` | Add | `String` | JDK type | 선택 후 실행할 검증 방향 |

### 7.3 HotfixSelection

| Field | Change Type | Type | Type Class | Change Detail |
| --- | --- | --- | --- | --- |
| `analysisId` | Add | `AnalysisId` | VO | 선택 대상 분석 |
| `candidateId` | Add | `CandidateId` | VO | 사용자가 선택한 후보 |
| `analysisVersion` | Add | `long` | primitive | 최신 후보 목록과 일치해야 함 |
| `idempotencyKey` | Add | `IdempotencyKey` | VO | 중복 branch/PR 생성 방지 |
| `selectedAt` | Add | `Instant` | JDK type | 명시적 선택 시각 |

### 7.4 IncidentPolicy

| Field | Change Type | Type | Type Class | Change Detail |
| --- | --- | --- | --- | --- |
| `mode` | Add | `AgentMode` | Enum | `REPORT_ONLY` 또는 `DRAFT_PR` |
| `allowedSourceTypes` | Add | `Set<SourceType>` | collection | `BRANCH`, `PULL_REQUEST` |
| `allowedEnvironments` | Add | `Set<Environment>` | collection | `DEV`, `QA`, `PROD` |
| `observabilityService` | Add | `ServiceTarget` | Enum | 항상 `EU_APP` |
| `maxObservabilityWindow` | Add | `Duration` | JDK type | request 범위 상한 60분 |
| `branchPrefix` | Add | `String` | Scalar | `agent/hotfix/` |
| `maxChangedFiles` | Add | `int` | Scalar | 10 |
| `maxChangedLines` | Add | `int` | Scalar | 500 |
| `maxPatchAttempts` | Add | `int` | Scalar | 2 |
| `forbiddenPaths` | Add | `List<PathPattern>` | Collection VO | migration, secret, Jenkinsfile, manifest |
| `operationsOwner` | Add | `String` | Scalar | `BE팀` |
| `reviewers` | Add | `List<String>` | Collection | 초기값 empty |

### 7.5 Evidence 모델

| Field | Change Type | Type | Type Class | Change Detail |
| --- | --- | --- | --- | --- |
| `buildEvidence` | Add | `Optional<BuildEvidence>` | Optional VO | build metadata, parsed failures, related test reports |
| `metricEvidence` | Add | `List<MetricEvidence>` | Collection VO | template ID, window, bounded samples와 요약 |
| `traceEvidence` | Add | `List<TraceEvidence>` | Collection VO | trace/span ID, service chain, error/latency 요약 |
| `logEvidence` | Add | `List<LogEvidence>` | Collection VO | labels, trace ID, redacted log excerpt |
| `provenance` | Add | `List<EvidenceProvenance>` | Collection VO | source URL, query template ID, 수집 시각 |
| `coverage` | Add | `EvidenceCoverage` | Enum | `SUFFICIENT`, `PARTIAL`, `INSUFFICIENT` |

### 7.6 Diagnosis와 Patch

| Field | Change Type | Type | Type Class | Change Detail |
| --- | --- | --- | --- | --- |
| `rootCause` | Add | `String` | Scalar | 증거로 지지되는 원인 |
| `confidence` | Add | `Confidence` | Value Object | 0~1과 근거 목록을 함께 보유 |
| `sourceLocations` | Add | `List<SourceLocation>` | Collection VO | repository-relative path와 line |
| `counterEvidence` | Add | `List<String>` | Collection | 가설에 반하는 증거 |
| `fixEligibility` | Add | `FixEligibility` | Enum | `ELIGIBLE`, `HUMAN_ONLY`, `INSUFFICIENT_EVIDENCE` |
| `patchIntent` | Add | `PatchIntent` | Value Object | 수정 대상 동작과 금지 범위 |
| `verificationPlan` | Add | `VerificationPlan` | Value Object | 재현 test와 module별 명령 |

### 7.7 종료 모델

| Model | 필수 조건 | 의미 |
| --- | --- | --- |
| `CandidatesPrepared` | version이 있는 `BugCandidate` 목록 생성 | 분석 API의 정상 결과, write 없음 |
| `NeedsHumanReview` | 자동 수정 거절 사유와 다음 확인 항목 | 안전한 비자동 종료 |
| `DraftPullRequestCreated` | 유효한 선택, policy/검증 통과, Draft PR 존재 | 선택 API로 달성하는 목표 |
| `IssueResolved` | policy 통과, Draft PR 존재, Jenkins PR build 성공 | 유일한 자동 해결 성공 상태 |

## 8. Port와 Adapter

| Port | Adapter | 허용 작업 |
| --- | --- | --- |
| `JenkinsQueryPort` | `JenkinsRestAdapter` | job/build metadata, console, test report GET |
| `LogQueryPort` | `LokiRestAdapter` | bounded `query_range` GET |
| `TraceQueryPort` | `TempoRestAdapter` | bounded search/trace GET |
| `MetricQueryPort` | `PrometheusRestAdapter` | bounded instant/range query GET |
| `SourceRepositoryPort` | `LocalGitRepositoryAdapter` | fetch, detached worktree, source read/search |
| `PatchWorkspacePort` | `LocalGitPatchAdapter` | allowed worktree 내 patch 적용과 diff 검사 |
| `VerificationPort` | `GradleVerificationAdapter` | allowlist command 실행 |
| `SourceRevisionPort` | `BitbucketRestAdapter` | branch head와 open PR source/destination 조회 |
| `PullRequestPort` | `BitbucketRestAdapter` | hotfix branch push, 지정 destination Draft PR 생성/조회 |
| `AnalysisStatePort` | `JsonFileAnalysisStateAdapter` | 분석, 후보, 선택과 hotfix 상태 원자적 저장 |

adapter에 제공하지 않을 기능:

- Jenkins build trigger/stop/configure
- Loki push/delete
- Tempo ingest/delete
- Prometheus admin API
- Grafana dashboard/Alert rule/contact point 수정
- Bitbucket merge/approve/tag/release
- Kubernetes write API

## 9. Embabel Action과 Goal

### 9.1 Action 목록

| Action | 입력 | 출력 | 성격 |
| --- | --- | --- | --- |
| `normalizeAnalysisRequest` | Jenkins/observability API request | `IncidentTrigger` | 결정론적 |
| `interpretNaturalLanguageCommand` | redaction된 자연어와 closed intent schema | `CommandInterpretationDraft` | LLM 추출 |
| `validateCommandInterpretation` | interpretation draft와 policy | confirmation/clarification/rejection artifact | 결정론적 gate |
| `dispatchConfirmedCommand` | version/hash가 확인된 typed command | 기존 use case request | 결정론적 application 호출 |
| `collectBuildEvidence` | Jenkins trigger | `BuildEvidence` | 결정론적 I/O |
| `planObservabilityEvidence` | observability trigger | `EvidencePlan` | template 선택 + 정책 검증 |
| `collectMetricEvidence` | evidence plan | `MetricEvidenceSet` | 결정론적 I/O |
| `collectTraceEvidence` | evidence plan | `TraceEvidenceSet` | 결정론적 I/O |
| `collectLogEvidence` | evidence plan/trace IDs | `LogEvidenceSet` | 결정론적 I/O |
| `buildBugCandidates` | available evidence | `CandidatesPrepared` | LLM 추론 + typed validation |
| `authorizeSelection` | selection/candidate/session | `SelectedCandidate` | 결정론적 gate |
| `loadRepositoryContext` | selected candidate/source revision | `RepositoryContext` | 결정론적 검색 |
| `proposePatch` | selected candidate/context/policy | `PatchProposal` | 고성능 LLM |
| `validatePatchPolicy` | patch proposal/diff | `PolicyDecision` | 결정론적 |
| `applyPatch` | approved proposal/worktree | `AppliedPatch` | 결정론적 write |
| `verifyFocusedPatch` | applied patch/focused plan | `FocusedVerificationResult` | 결정론적 process |
| `reviewPatch` | diagnosis/diff/test result | `PatchReview` | 독립 LLM 검토 |
| `verifyJenkinsParity` | approved patch/fixed Jenkinsfile profile | `JenkinsParityVerification` | 결정론적 process |
| `publishDraftPullRequest` | approved patch/review/parity success | `DraftPullRequest` | 결정론적 write |
| `refreshPullRequestBuildStatus` | explicit CI refresh request | `PullRequestBuildResult` | 결정론적 GET |
| `completeIssue` | successful PR build | `IssueResolved` | goal 달성 |
| `routeToHuman` | unsafe/insufficient/failed state | `NeedsHumanReview` | 안전 종료 |

Action 간에는 큰 mutable context를 공유하지 않고 typed artifact만 blackboard에 추가한다. 각 외부 write
Action은 재실행 전에 idempotency key로 기존 branch/PR을 조회한다.

### 9.2 Goal

- `CandidatesPrepared`: API 요청에 대해 versioned 후보 목록이 생성되었다.
- `CommandReadyForConfirmation`: 실행 없이 구조화된 명령 미리보기가 생성되었다.
- `NeedsClarification`: 실행에 필요한 필드와 질문이 생성되었다.
- `CommandRejected`: 지원하지 않거나 정책을 우회하는 명령이 안전하게 거절되었다.
- `DraftPullRequestCreated`: 명시적으로 선택된 후보의 Draft PR이 생성되었다.
- `IssueResolved`: 명시적 CI refresh에서 Draft PR의 Jenkins build 성공이 확인되었다.
- `NeedsHumanReview`: 정책 위반, 근거 부족 또는 검증 실패 사유가 구조화되었다.

`@AchievesGoal`은 위 조건을 실제로 검증한 마지막 Action에만 붙인다. PR을 만들었다는 이유만으로
`IssueResolved`를 반환하지 않는다. 후보 선택 전에는 patch 관련 Action의 입력 타입 자체가 존재하지 않아
planner가 write 경로를 구성할 수 없어야 한다.

### 9.3 동적 계획 예시

| 사건 | 예상 Action 경로 |
| --- | --- |
| Checkstyle 실패 | build evidence → source → patch → policy → focused verification → review → Jenkins parity → Draft PR |
| unit test 실패 | build evidence → source/test → diagnosis → patch → focused test → review → Jenkins parity → Draft PR → PR CI |
| 지정 시각 HTTP 5xx 신호 | metrics → error traces → trace-linked logs → 후보 목록 → 선택 → patch → review → Jenkins parity → Draft PR |
| 지정 시각 JVM memory 신호 | metrics/logs → 인프라/용량 여부 후보 → 대부분 human review |
| 지정 시각 DB connection 신호 | metrics/traces/logs → secret/infra 후보 → human review |

## 10. 모델 역할 배정

모델명을 Java 코드에 하드코딩하지 않는다.

| 역할 | 환경변수 | 작업 | 초기 운영 방식 |
| --- | --- | --- | --- |
| triage | `LITELLM_TRIAGE_MODEL` | 로그 축약, stage/증상 분류 | 미설정 시 `LITELLM_MODEL` |
| reasoning | `LITELLM_REASONING_MODEL` | 상관관계, 원인 진단, 패치 생성 | 미설정 시 `LITELLM_MODEL` |
| review | `LITELLM_REVIEW_MODEL` | 반례 탐색, diff 독립 검토 | 미설정 시 reasoning model |

현재 선택한 `gemma-4.26b` 하나로 end-to-end 연결을 먼저 검증한다. 역할 분리는 동일 사건 fixture에
대한 품질과 응답 시간을 비교한 뒤 활성화한다. 모델 이름의 크기나 공급자만 보고 자동으로 중요한
수정 권한을 넓히지 않는다.

모든 LLM 출력은 typed DTO로 역직렬화하고 schema validation 실패 시 한 번만 재요청한다. 두 번째
실패는 `NeedsHumanReview`로 보낸다.

## 11. Git, 패치, 검증 정책

### 11.1 작업 공간

1. `/Users/dykim/workspace/fms`에서 요청에 지정된 원격 branch 또는 PR ref를 fetch한다.
2. 분석 시작 시 source commit SHA와 Draft PR destination branch를 고정한다.
3. 사건별 detached worktree를 `.agent/runtime/worktrees/{incidentId}`에 만든다.
4. `agent/hotfix/{incidentId}-{slug}` branch를 만든다.
5. patch 적용 전후 `git diff --numstat`, `--name-only`로 정책을 검사한다.

기존 사용자의 FMS working tree가 dirty여도 건드리지 않는다.

### 11.2 금지 경로

최소 다음 패턴을 거부한다.

- `**/db/changelog/**`, `**/migration/**`, `**/*Migration*`
- `**/*secret*`, `.env*`, 인증서와 key 파일
- `**/Jenkinsfile`, `Jenkinsfile`
- `**/k8s/**`, `**/helm/**`, `**/manifests/**`, `**/values*.yml`
- `fms-deploy` 저장소의 모든 파일

### 11.3 수정 반복 중 focused 검증

재현 test를 먼저 추가하거나 기존 실패 test를 그대로 사용한다. 변경 module과 실패 유형에 따라
Jenkinsfile에 이미 존재하는 명령의 부분 집합을 실행한다. 이 결과는 수정 피드백을 빠르게 얻기 위한
것이며 Draft PR 생성 자격을 부여하지 않는다.

| 변경 범위 | 최소 로컬 검증 |
| --- | --- |
| `eu-app` Java | `:eu:eu-app:test`, `architectureTest`, `checkstyleMain` |
| `eu-app` persistence/integration | 위 항목 + 관련 `integrationTest`; migration 변경은 자동 수정 불가 |
| `eu-gateway` | `:eu:eu-gateway:test`, `checkstyleMain` |
| `eu-metrics` | `:eu:eu-metrics:test`, `architectureTest`, `checkstyleMain` |
| 공통 Gradle/config | 자동 수정 대상에서 기본 제외하거나 전체 CI 요구 |

검증 실패 시 진단과 diff를 함께 Embabel blackboard에 추가하여 최대 2회까지 재계획한다. 재시도는
같은 코드를 반복 생성하는 것이 아니라 실패 원인이 새 증거로 추가된 경우에만 허용한다.

### 11.4 Draft PR 직전 Jenkins 동등 검증

독립 review가 승인한 뒤, Draft PR을 만들기 전에 수정된 동일 worktree HEAD에서
`JENKINS_PR_PARITY` profile을 처음부터 끝까지 실행한다. profile은 분석 시 고정한 source revision의
`eu/Jenkinsfile` path와 content hash에 연결한다. 승인된 profile registry에 해당 hash가 없으면
Groovy를 임의 해석하거나 기존 profile을 재사용하지 않고 `NeedsHumanReview`로 종료한다.

현재 Jenkinsfile 기준 필수 단계는 다음과 같다.

1. `Gradle verification`: architecture, Checkstyle, unit/integration/migration/external API test와 JaCoCo
   report task 전체
2. `Test Coverage`: 생성된 JaCoCo report를 `eu/ci/print-jacoco-coverage.sh`로 확인
3. `Image Build`: `eu-app`, `eu-gateway`, `eu-metrics`의 local Jib Docker image build
4. `Integration Test`: 격리된 Compose project로 세 application health check와 Newman suite 실행

Gradle 단계는 Jenkinsfile의 다음 task 집합을 축소 없이 사용한다.

```text
:eu:eu-app:architectureTest
:eu:eu-app:checkstyleMain
:eu:eu-gateway:checkstyleMain
:eu:eu-metrics:architectureTest
:eu:eu-metrics:checkstyleMain
:eu:eu-app:integrationTest
:eu:eu-app:migrationTest
:eu:eu-app:test
:eu:eu-gateway:test
:eu:eu-metrics:test
:eu:eu-app:jacocoTestReport
:eu:eu-app:jacocoIntegrationTestReport
:eu:eu-app:jacocoTestAndIntegrationTestReport
:eu:eu-gateway:jacocoTestReport
:eu:eu-metrics:jacocoTestReport
:eu:eu-app:externalApiTest
```

배포, ECR push와 manifest 갱신은 테스트가 아니며 금지된 외부 변경이므로 profile에서 제외한다.
반대로 Docker/Jib 또는 Newman 실행 환경이 없다는 이유로 검증 단계를 건너뛸 수 없다. 단계가 실패,
skip 또는 실행 불가이면 `NeedsHumanReview`로 종료하고 Draft PR을 만들지 않는다.

결과에는 다음 provenance를 저장한다.

- base commit과 검증한 patch commit
- `eu/Jenkinsfile` path와 SHA-256
- verification profile version
- 실행한 stage/task, 시작·종료 시각, exit code
- 생성 image tag와 Compose project name

PR write 직전에 worktree HEAD가 검증한 patch commit과 같은지 확인한다. review 수정 등으로 한 줄이라도
변경되면 기존 parity 결과를 폐기하고 전체 profile을 다시 실행한다.

## 12. Draft PR 형식

제목 예시:

```text
[Agent Hotfix] Fix <service> <concise symptom>
```

본문 필수 항목:

- Analysis ID와 source(Jenkins 또는 Grafana 관측)
- 기준 source type, branch/PR 번호, 고정 base commit과 Draft PR destination
- 관측 환경, 고정 대상 `eu-app`, 운영 담당자 `BE팀`
- 마스킹된 증거와 원인 요약
- 변경 파일/줄 수와 정책 검사 결과
- 수행한 로컬 검증과 결과
- Jenkins 동등 검증 profile, Jenkinsfile hash, 검증 commit과 전체 stage 결과
- Jenkins PR build URL과 결과
- 알려진 한계와 rollback 설명
- `Generated as Draft — human approval required` 문구

reviewer 요청은 보내지 않는다. PR은 항상 Draft이며 merge 관련 API는 client interface 자체에 두지 않는다.

## 13. 설정 모델

`.env.local`에는 secret과 로컬 실행값을 두고 Git에 포함하지 않는다. YAML에는 환경변수 참조와
안전한 기본값만 둔다.

추가할 설정 범주:

```text
AGENT_MODE=REPORT_ONLY
AGENT_FMS_REPOSITORY_PATH=/Users/dykim/workspace/fms
AGENT_ANALYSIS_TTL=24h
AGENT_API_BIND_ADDRESS=127.0.0.1

GRAFANA_BASE_URL=
GRAFANA_TOKEN=
GRAFANA_TLS_VERIFY=false
GRAFANA_LOKI_DATASOURCE_UID=P8E80F9AEF21F6940
GRAFANA_PROMETHEUS_DATASOURCE_UID=prometheus
GRAFANA_TEMPO_DATASOURCE_UID=tempo
LOKI_BASE_URL=
LOKI_TOKEN=
LOKI_TENANT_ID=
TEMPO_BASE_URL=
TEMPO_TOKEN=
TEMPO_TENANT_ID=
PROMETHEUS_BASE_URL=
PROMETHEUS_TOKEN=

LITELLM_TRIAGE_MODEL=
LITELLM_REASONING_MODEL=
LITELLM_REVIEW_MODEL=
```

실제 prod 구성은 Grafana만 외부 Ingress로 노출하고 데이터 소스는 ClusterIP로 제공한다. 따라서
MVP는 Grafana datasource proxy adapter 하나만 구현한다. `LOKI_BASE_URL`, `TEMPO_BASE_URL`,
`PROMETHEUS_BASE_URL`은 향후 direct endpoint가 제공될 때만 사용하며 초기 구현에서는 요구하지 않는다.

## 14. 테스트 전략

운영 API를 직접 호출하는 테스트와 agent 계획 테스트를 분리한다.

| Test | Fixture/방법 | 통과 기준 |
| --- | --- | --- |
| analysis idempotency | 동일 key로 Jenkins/observability 요청 반복 | 동일 `analysisId`와 결과 반환 |
| no automatic polling | 시간이 경과해도 외부 mock 호출 없음 | API 요청 시에만 외부 조회 발생 |
| natural-language preview | 한국어/영어 Jenkins·관측 요청 | 같은 typed command, 확인 전 external tool 0회 |
| natural-language ambiguity | ID/시간/source가 빠진 요청 | 명확화 질문, command hash 없음 |
| natural-language injection | 정책 무시, raw query, shell, merge/deploy 문구 | 거절, tool/Git/PR 호출 0회 |
| natural-language confirmation | 만료 또는 변조된 version/hash | `409`, use case 위임 0회 |
| natural-language parity | 자연어와 구조화 API의 동등 요청 | 같은 typed use-case input과 safety gate 결과 |
| Jenkins parser | 저장한 성공/실패 console fixture | 실패 stage, module, source 위치 추출 |
| observability scope | 시작/종료 시각과 `DEV/QA/PROD` 입력 | 지정 범위와 환경의 `eu-app` template만 실행 |
| observability range | 역전, 빈 범위, 61분 범위 입력 | `400`, 외부 호출 없음 |
| forbidden observability input | service/query/observedAt 필드 추가 | `400`, 외부 호출 없음 |
| candidate boundary | 후보 목록 생성 후 diff/worktree 검사 | 선택 전 파일 변경과 branch가 없음 |
| stale selection | 이전 `analysisVersion` 선택 | `409 Conflict`, write 없음 |
| invalid selection | 다른 analysis의 candidate 선택 | `404` 또는 `422`, write 없음 |
| selection idempotency | 동일 selection key 반복 | 동일 `hotfixId`, PR 중복 없음 |
| branch source | 존재하는 원격 branch 입력 | 해당 branch head에서 분기하고 동일 branch로 Draft PR |
| pull request source | open PR 번호 입력 | PR source commit에서 분기하고 PR source branch로 Draft PR |
| invalid source | 없는 branch 또는 merged/declined PR | 분석 전에 거부, Git write 없음 |
| moved source | 분석 후 branch/PR source commit 변경 | stale selection `409`, 재분석 요구 |
| query safety | 역전/과도한 시간 범위 또는 결과 limit 초과 | 외부 호출 전에 거부 |
| redaction | token, password, PII fixture | LLM request와 report에 원문 없음 |
| Embabel planning | compile/test/5xx/memory 사건 fixture | 사건별 필요한 action만 선택 |
| policy guard | 금지 경로, 11 files, 501 lines | patch 적용/PR 생성 전 거부 |
| retry limit | 연속 verification 실패 | 두 번째 실패 후 human review |
| parity coverage | Jenkinsfile stage fixture와 profile 비교 | 배포 제외 필수 stage/task 누락 없음 |
| parity failure | focused 성공 후 image/Newman stage 실패 | human review, Draft PR 없음 |
| parity unavailable | Docker 또는 필수 local dependency 없음 | skip하지 않고 human review, Draft PR 없음 |
| parity commit binding | parity 성공 후 worktree 변경 | 결과 무효화, 전체 parity 재실행 전 PR 없음 |
| idempotent PR | 동일 incident 재실행 | branch/PR 추가 생성 없음 |
| success goal | Draft PR만 존재 | `IssueResolved` 미달성 |
| success goal | Draft PR + Jenkins SUCCESS | `IssueResolved` 달성 |
| architecture | 기존 architecture test | Spring AI 직접 의존 및 slice 침범 없음 |
| capability budget | agent manifest에 skill/tool 각각 5개와 6개 fixture | 5개 통과, 6개는 agent와 초과 ID를 표시하며 실패 |
| child ownership | 부모 manifest에 자식 전용 tool 추가 | architecture test 실패, typed artifact 연결만 허용 |

WireMock 또는 MockWebServer로 외부 REST adapter를 검증하고, 실제 credential을 사용하는 smoke test는
별도 Gradle task/profile로 분리하여 기본 `check`에서 실행하지 않는다.

## 15. 구현 순서

### Phase 0 — 운영 연결점 확정

완료:

1. AWS 세션을 갱신했다.
2. `fms-prod`의 Grafana/Loki/Tempo/Prometheus/Alertmanager Service와 Ingress를 읽기 전용으로 확인했다.
3. Mac에서 Grafana external endpoint까지 연결하고 `--insecure` health 조회를 확인했다.
4. Prometheus와 Tempo datasource UID를 확인했다.
5. Grafana read-only service account token을 `.env.local`에 저장했다.
6. Grafana API로 Loki datasource UID를 확인했다.
7. `DEV/QA/PROD`별 namespace와 `eu-app`의 Loki/Tempo/Prometheus label mapping을 확인했다.
8. setup/run script에 datasource UID 자동 탐색과 로컬 실행 정책 설정을 추가했다.

검증: secret 값을 출력하지 않고 각 API의 health/build-info 또는 제한된 metadata 조회가 성공한다.

### Phase 1 — API 계약, 정책, 분석 상태 저장소

구현 완료 범위: idempotency/request hash, JSON atomic state, `202 Accepted` resource 응답,
`ANALYSIS_REQUESTED → ANALYZING → CANDIDATES_READY|FAILED` 및 `SELECTED → hotfix workflow` 비동기 실행.
background polling이나 scheduler는 등록하지 않는다.

1. Jenkins/observability 분석, 후보 조회, 선택, hotfix 상태 API 계약을 구현한다.
2. 자연어 interpretation/confirmation 계약, closed intent validator와 command hash를 구현한다.
3. `AnalysisSession`, `BugCandidate`, `HotfixSelection`, `IncidentPolicy`를 구현한다.
4. JSON file state adapter와 idempotency/version test를 구현한다.
5. 금지 경로/변경량 및 자연어 bypass policy test를 먼저 작성한다.

검증: API contract, stale selection, policy와 재시작 복구 test가 외부 API 없이 통과한다.

### Phase 2 — Jenkins 읽기 전용 진단

구현 완료 범위: 지정 실패 build의 metadata/console/test report 조회, 관련 로그 최대 200줄 축약,
credential/식별자 redaction, build revision과 source commit 검증, 증거 경로의 Bitbucket source context 조회.

1. Jenkins 분석 API에서 호출하는 REST adapter를 구현한다.
2. console/test report parser를 구현한다.
3. 저장된 실제 실패 fixture로 `BugCandidate` 목록까지 생성한다.

검증: API 요청이 없을 때 Jenkins를 호출하지 않으며, 요청 후에도 코드를 수정하거나 PR을 만들지 않고
후보 목록만 생성한다.

### Phase 3 — 운영 관측 읽기 전용 진단

구현 완료 범위: 명시적 시작/종료 시각과 환경 입력, `fms-eu-{env}` 및
`service_name=fms-eu-{env}-app` 고정 query, Loki 500 lines/Prometheus 100 data-point request bound,
Tempo 20 trace search, EU app alert 필터와 민감 정보 redaction.

1. `startAt`, `endAt`, `environment`, `source`만 받는 관측 분석 API를 구현한다.
2. 환경별 namespace와 고정 `EU_APP` scope mapper를 구현한다.
3. Prometheus, Tempo, Loki adapter와 query template registry를 구현한다.
4. trace ID 기반 correlation을 구현한다.
5. redaction, unknown field 거부와 query bound test를 통과시킨다.

검증: 지정 시간 범위와 환경에서 `eu-app`의 source 후보와 반례를 포함한 목록을 만들며 다른 service 데이터는
결과와 LLM 입력에 포함되지 않는다.

### Phase 4 — Embabel 진단 에이전트

구현 완료 범위: annotation agent 자동 선택, typed candidate/patch/review 결과, triage/reasoning/review
역할별 모델 설정, 실제 agent capability manifest와 5개 제한 architecture test.

1. 분석용 action과 선택 이후 hotfix action을 typed artifact로 분리한다.
2. triage/reasoning/review 모델 역할을 설정한다.
3. agent/subagent별 capability manifest와 skill/tool 최대 5개 architecture test를 구현한다.
4. 사건별 plan test와 structured output validation을 구현한다.

검증: Jenkins 컴파일 실패에는 관측 API가 호출되지 않고, 지정 시각의 5xx 신호에는 필요한 관측
action만 실행된다.
선택 입력이 없으면 patch와 PR action은 plan에 포함되지 않는다.

### Phase 5 — Shadow 운영

분석 API를 통해 `REPORT_ONLY`로 최소 5개의 서로 다른 사건 또는 합의한 시연 fixture를 처리한다.

검증 항목:

- source 위치 적중률
- 원인 진단에 사용한 증거의 추적 가능성
- 오탐과 불필요한 query 수
- PII/secret redaction 누락
- 평균 LLM 호출 수와 처리 시간

이 결과를 BE팀이 확인하기 전에는 `DRAFT_PR` 모드를 켜지 않는다.

### Phase 6 — 안전한 patch와 로컬 검증

구현 완료 범위: source commit 전용 worktree, evidence file scope, 10 files/500 lines 및 금지 경로의
사전·사후 gate, focused 검증과 최대 2회 재시도, 독립 Embabel review, Jenkinsfile hash 기반 parity.

1. candidate 선택 API와 version/eligibility/idempotency gate를 구현한다.
2. 선택된 hotfix별 worktree와 branch adapter를 구현한다.
3. patch 적용 전후 policy gate를 구현한다.
4. focused Gradle verification과 최대 2회 재계획을 구현한다.
5. 독립 review model이 반례와 scope를 검토한다.
6. 고정 Jenkinsfile 기반 `JENKINS_PR_PARITY` 전체 검증과 결과 provenance를 구현한다.

검증: 선택 전에는 파일 변경이 없고, 허용 fixture는 focused 및 parity 검증을 모두 통과하며, 금지 경로
fixture는 파일이 수정되기 전에 거부된다. parity stage 실패·skip·실행 불가 fixture에는 PR이 없다.

### Phase 7 — Draft PR과 Jenkins PR 검증

구현 완료 범위: source freshness 재검사, 임시 `GIT_ASKPASS`를 이용한 hotfix branch push, reviewer가
없는 Bitbucket Draft PR, 동일 patch commit 검증, 명시적 CI status refresh. merge/tag/deploy API는 없다.

1. 유효한 `JENKINS_PR_PARITY=SUCCESS`와 동일 worktree HEAD에서만 동작하는 Bitbucket Draft PR 생성과
   idempotency를 구현한다.
2. `POST /api/v1/hotfixes/{hotfixId}/ci-status-refresh` 호출 시 생성 PR에 대응하는 Jenkins `PR-*`
   job 상태를 한 번 조회한다.
3. 성공 시에만 `IssueResolved`, 실패/진행 중이면 현재 상태를 반환한다.

검증: background polling 없이 명시적 refresh 호출로만 CI 상태가 바뀌며, merge/tag/deploy 없이 Draft
PR과 성공 CI 링크가 포함된 최종 결과가 생성된다.

## 16. 영향 파일 계획

아래는 구현 시 예상되는 경계이며 실제 package 명은 현재 architecture test 규칙에 맞춰 확정한다.

| File/Directory | Change Type | Purpose |
| --- | --- | --- |
| `app/src/main/java/com/example/myagent/hotfixapi/**` | Add | 분석, 후보 목록, 선택, hotfix 상태 REST API |
| `app/src/main/java/com/example/myagent/command/**` | Add | 자연어 interpretation, confirmation, typed command dispatch와 guardrail |
| `app/src/main/java/com/example/myagent/incident/**` | Add | analysis/candidate/selection model과 상태 관리 |
| `app/src/main/java/com/example/myagent/jenkins/**` | Add | Jenkins query port/adapter/parser |
| `app/src/main/java/com/example/myagent/observability/**` | Add | 시간 범위/환경 scope, eu-app mapper, Loki/Tempo/Prometheus adapter/templates |
| `app/src/main/java/com/example/myagent/repository/**` | Add | local Git worktree, source search, patch policy |
| `app/src/main/java/com/example/myagent/verification/**` | Add | Gradle verification selection/execution |
| `app/src/main/java/com/example/myagent/pullrequest/**` | Add | Bitbucket Draft PR adapter |
| `app/src/main/java/com/example/myagent/hotfixagent/**` | Add | Embabel actions/goals/model transforms |
| `app/src/main/java/com/example/myagent/global/configuration/**Properties.java` | Add/Modify | typed integration/policy properties |
| `app/src/main/resources/application-local.yml` | Modify | environment variable bindings |
| `app/src/test/**` | Add/Modify | unit, architecture, plan, adapter contract tests |
| `scripts/setup-env-local.zsh` | Modify | 새 endpoint/token/model role 입력 |
| `.env.local.example` | Add | secret 없는 변수 이름과 기본값 예시 |

## 17. 데이터 마이그레이션

DB migration은 없다. 로컬 JSON incident schema에는 `schemaVersion`을 둔다. 호환되지 않는 개발 단계
변경은 `.agent/runtime`을 수동 백업한 뒤 초기화할 수 있으나 FMS 저장소나 운영 데이터에는 영향을
주지 않는다.

## 18. 명시적으로 제외하는 범위

- 자동 merge, tag, release, deploy, rollback
- Grafana dashboard/Alert rule 자동 변경
- Kubernetes resource 자동 수정 또는 pod 재시작
- DB migration과 운영 데이터 수정
- secret 회전 또는 권한 변경
- Slack/Jira 자동 통지
- 여러 로컬 agent 인스턴스의 분산 lock
- 자유 형식 자연어를 shell command나 관측 query로 직접 실행. 단, 폐쇄 intent로 해석하고 사용자가
  version/hash를 확인한 뒤 기존 구조화 use case에 위임하는 자연어 API는 포함한다.

## 19. 구현 시작 전 결정할 항목

Phase 0의 연결 설정은 완료했다. 구현 중 다음 항목만 선정한다.

1. 첫 shadow test에 사용할 실패 Jenkins build 또는 관측 시간 범위/환경 fixture

나머지 정책은 이 문서의 기본값으로 고정한다.

## 20. Notes

- 시연 중 `JENKINS_TLS_VERIFY=false`와 `GRAFANA_TLS_VERIFY=false`를 유지한다. Grafana proxy 뒤의 Loki,
  Tempo, Prometheus에는 별도의 TLS 비활성화를 적용하지 않는다.
- 요청한 branch 또는 PR source commit을 분석 시작 시 고정한다. 선택 시 원격 source가 이동했다면
  stale selection으로 거부하고 재분석하게 한다.
- 운영 증거가 많다는 이유로 confidence를 높이지 않는다. 서로 독립적인 metric, trace, log와 source
  근거가 같은 원인을 지지할 때만 자동 수정 자격을 준다.
- 기존 구현 계획의 EKS 내부 배포와 Jenkins callback 가정은 이 로컬 실행 설계로 대체한다.
