# CI/CD 및 관측 장애 핫픽스 에이전트 소프트웨어 요구사항 명세서

## 1. 문서 정보

| 항목 | 내용 |
| --- | --- |
| 문서명 | Software Requirements Specification (SRS) |
| 시스템명 | Embabel 기반 FMS 핫픽스 에이전트 |
| 버전 | 1.3 |
| 상태 | 구현 기준선 |
| 기준일 | 2026-08-24 |
| 상위 요구사항 | [CRS](CRS.md) |
| 설계 근거 | [운영 장애 핫픽스 에이전트 설계](../design/observability-hotfix-agent.md) |
| API 설계 | [Hotfix Agent API](../api/hotfix-agent-api.md) |

## 2. 목적과 범위

이 문서는 CRS를 구현하고 검증하기 위한 소프트웨어 요구사항을 정의한다. 시스템은 로컬 Mac에서
Spring Boot와 Embabel로 실행되며, API 요청으로 전달된 Jenkins 실패 또는 Grafana 관측 범위만
분석한다. 분석과 변경은 분리되며 사용자가 후보를 선택하기 전에는 어떠한 Git write도 수행하지 않는다.

## 3. 용어

| 용어 | 정의 |
| --- | --- |
| Analysis | 외부 증거와 source를 읽어 versioned `BugCandidate` 목록을 만드는 읽기 전용 작업 |
| BugCandidate | 독립된 원인 가설, 근거, 관련 소스와 수정 가능 여부를 가진 분석 결과 |
| Selection | 사용자가 candidate ID와 analysis version을 지정하여 수정 대상을 승인하는 행위 |
| Hotfix | 선택된 후보에 대해 patch, 검증과 Draft PR 생성을 수행하는 작업 |
| Source | Bitbucket branch 또는 open PR source commit |
| ObservationWindow | Grafana 관측 탐색의 시작 시각과 종료 시각 범위 |
| IssueResolved | Draft PR의 Jenkins PR build 성공까지 명시적으로 확인된 상태 |
| CommandInterpretation | 자연어에서 폐쇄된 intent와 typed parameter만 추출한 실행 전 미리보기 |
| CommandHash | 정규화된 intent와 parameter에 대한 hash로 사용자가 확인한 명령을 고정하는 값 |

## 4. 시스템 구성과 경계

### 4.1 논리 구성

| 구성 요소 | 책임 |
| --- | --- |
| Hotfix API | 분석 요청, 후보 조회, 선택, hotfix 상태와 CI refresh API 제공 |
| Incident Analysis Agent | Embabel action으로 제한된 증거와 source context에서 후보 생성 |
| Patch Author/Review Agent | 선택된 후보의 patch 제안과 독립 검토 |
| Hotfix Workflow | 정책 검사, worktree, parity 검증과 Draft PR을 결정론적으로 조율 |
| Jenkins Adapter | build metadata, console, test report와 PR CI 상태 조회 |
| Grafana Proxy Adapter | Loki, Tempo, Prometheus 읽기 API 호출 |
| Bitbucket Adapter | source 확인, hotfix branch push, Draft PR 생성/조회 |
| Local Git Adapter | detached worktree, source 검색, patch와 diff 검사 |
| Analysis State Store | analysis, candidate, selection과 hotfix 상태의 로컬 영속화 |
| Natural Language Command Agent | 자연어를 실행하지 않고 구조화된 명령 또는 명확화 질문으로 변환 |
| Operator Dashboard | HTMX SSR로 실패 PR, 관측 신호, 자연어 확인과 hotfix 진행 상태 제공 |

### 4.2 기술 경계

- Production code는 Embabel을 통해 LLM을 사용해야 하며 `org.springframework.ai`에 직접 의존하지 않는다.
- 외부 시스템 I/O와 Git/process write는 결정론적 Java adapter만 수행한다.
- 시스템은 merge, approve, tag, release, deploy와 Kubernetes write client를 제공하지 않는다.
- 기본 HTTP bind address는 `127.0.0.1`이어야 한다.

## 5. 기능 요구사항

### 5.1 공통 API 처리

| ID | 소프트웨어 요구사항 | 검증 방법 |
| --- | --- | --- |
| SRS-API-001 | 모든 분석 및 선택 POST API는 `Idempotency-Key` header를 요구해야 한다. | header 누락 시 `400` contract test |
| SRS-API-002 | 동일 endpoint, request hash와 idempotency key의 반복 호출은 기존 resource를 반환해야 한다. | 반복 호출 integration test |
| SRS-API-003 | 같은 idempotency key에 다른 request body가 전달되면 `409 Conflict`를 반환해야 한다. | conflict test |
| SRS-API-004 | JSON 역직렬화는 unknown field를 거부해야 한다. | 미지원 필드 `400` test |
| SRS-API-005 | 장시간 작업의 최초 요청은 `202 Accepted`, resource ID와 상태 조회 URL을 반환해야 한다. | API contract test |
| SRS-API-006 | 시스템에는 Jenkins/Grafana 분석을 시작하는 scheduler 또는 polling task가 없어야 한다. | architecture 및 시간 경과 mock test |

### 5.2 Jenkins 분석 API

#### 5.2.1 Endpoint

```http
POST /api/v1/analyses/jenkins
```

필수 request:

| Field | Type | 규칙 |
| --- | --- | --- |
| `jobPath` | string | `FMS-EU` root 하위 job만 허용 |
| `buildNumber` | positive integer | Jenkins에 존재하는 완료 build |
| `source` | `BranchSource` 또는 `PullRequestSource` | 5.4절 규칙 적용 |

| ID | 소프트웨어 요구사항 | 검증 방법 |
| --- | --- | --- |
| SRS-JEN-001 | 시스템은 request로 지정된 build의 metadata, console text와 test report만 조회해야 한다. | Jenkins mock request 검증 |
| SRS-JEN-002 | 분석 대상 build가 실패 상태가 아니면 `422 Unprocessable Entity`를 반환해야 한다. | SUCCESS build fixture |
| SRS-JEN-003 | Jenkins build revision과 resolved source commit이 다르면 분석을 시작하지 않아야 한다. | revision mismatch test |
| SRS-JEN-004 | console 전체가 아니라 실패 stage, exception chain, 실패 test와 관련 줄만 LLM 입력으로 구성해야 한다. | prompt capture fixture |
| SRS-JEN-005 | LLM에 전달하는 Jenkins 관련 log는 최대 200 lines여야 한다. | boundary test |

### 5.3 Grafana 관측 분석 API

#### 5.3.1 Endpoint

```http
POST /api/v1/analyses/observability
```

필수 request:

| Field | Type | 규칙 |
| --- | --- | --- |
| `startAt` | offset datetime | timezone offset 필수, `endAt`보다 이전 |
| `endAt` | offset datetime | timezone offset 필수, `startAt`보다 이후 |
| `environment` | enum | `DEV`, `QA`, `PROD`만 허용 |
| `source` | `BranchSource` 또는 `PullRequestSource` | 5.4절 규칙 적용 |

예시:

```json
{
  "startAt": "2026-08-21T12:50:00+09:00",
  "endAt": "2026-08-21T13:10:00+09:00",
  "environment": "PROD",
  "source": {
    "type": "PULL_REQUEST",
    "pullRequestId": 1285
  }
}
```

| ID | 소프트웨어 요구사항 | 검증 방법 |
| --- | --- | --- |
| SRS-OBS-001 | 시스템은 환경을 `DEV=fms-eu-dev`, `QA=fms-eu-qa`, `PROD=fms-eu-prod` namespace로 매핑해야 한다. | parameterized unit test |
| SRS-OBS-002 | 시스템은 관측 service를 `EU_APP`으로 내부 고정하고 request에서 service 또는 namespace를 받지 않아야 한다. | unknown field `400` test |
| SRS-OBS-003 | 각 datasource query의 시작과 종료 시각은 request의 `startAt`, `endAt`과 일치해야 한다. | adapter argument test |
| SRS-OBS-004 | `startAt`은 `endAt`보다 앞서야 하며 두 시각의 차이는 31일 이하여야 한다. | 0/31일/31일 초과 boundary test |
| SRS-OBS-005 | request에 `service`, `namespace`, `promql`, `logql`, `traceql`, `observedAt`, `windowMinutes`가 있으면 `400`을 반환해야 한다. | forbidden input test |
| SRS-OBS-006 | 시스템은 allowlist의 query template과 환경/service/시간 범위 parameter만 사용해야 한다. | query registry test |
| SRS-OBS-007 | Prometheus 응답은 template당 최대 100 series로 제한해야 한다. | oversized response test |
| SRS-OBS-008 | Tempo 검색은 최대 20 traces, 상세 조회는 최대 3 traces로 제한해야 한다. | adapter boundary test |
| SRS-OBS-009 | Loki 결과는 최대 500 lines 및 2 MB로 제한해야 한다. | response boundary test |
| SRS-OBS-010 | `eu-app` 외 service의 metric, span 또는 log는 후보 생성과 LLM 입력에서 제거해야 한다. | mixed-service fixture test |
| SRS-OBS-011 | Grafana 접근은 datasource proxy와 read-only token을 사용해야 한다. | HTTP adapter test |

### 5.4 Source resolution

`source`는 discriminator `type`을 사용하는 tagged union이어야 한다.

#### BranchSource

```json
{
  "type": "BRANCH",
  "branchName": "main"
}
```

#### PullRequestSource

```json
{
  "type": "PULL_REQUEST",
  "pullRequestId": 1285
}
```

| ID | 소프트웨어 요구사항 | 검증 방법 |
| --- | --- | --- |
| SRS-SRC-001 | `BranchSource`는 `autocrypt/fms`에 존재하는 원격 branch만 허용해야 한다. | Bitbucket mock test |
| SRS-SRC-002 | `PullRequestSource`는 `OPEN` 상태 PR만 허용해야 한다. | merged/declined fixture |
| SRS-SRC-003 | branch 입력은 branch head를 base commit과 Draft PR destination으로 고정해야 한다. | source resolution test |
| SRS-SRC-004 | PR 입력은 PR source commit을 base commit, PR source branch를 Draft PR destination으로 고정해야 한다. | PR resolution test |
| SRS-SRC-005 | 시스템은 analysis에 base commit, destination과 source provenance를 저장해야 한다. | persistence test |
| SRS-SRC-006 | selection 시 원격 source commit을 재조회하여 저장된 base와 다르면 `409 Conflict`를 반환해야 한다. | stale source test |
| SRS-SRC-007 | 시스템은 source branch에 직접 push하지 않아야 한다. | Git/Bitbucket interaction test |

### 5.5 증거와 후보 생성

| ID | 소프트웨어 요구사항 | 검증 방법 |
| --- | --- | --- |
| SRS-CAN-001 | 분석은 Git write 없이 source file을 읽고 검색할 수 있어야 한다. | filesystem snapshot test |
| SRS-CAN-002 | 분석 결과는 version을 가진 `AnalysisSession`과 0개 이상의 `BugCandidate`로 저장해야 한다. | serialization test |
| SRS-CAN-003 | 각 candidate는 ID, 제목, 원인, confidence, source locations, evidence refs, counter evidence, eligibility, 수정 및 검증 요약을 포함해야 한다. | schema test |
| SRS-CAN-004 | candidate ID는 같은 analysis 결과를 재조회할 때 안정적이어야 한다. | repeated read test |
| SRS-CAN-005 | evidence가 불충분하거나 코드 수정 대상이 아니면 candidate를 `HUMAN_ONLY` 또는 `INSUFFICIENT_EVIDENCE`로 표시해야 한다. | classification fixture |
| SRS-CAN-006 | 분석 완료 전과 후보 조회 시점까지 worktree, branch, diff와 PR이 존재하지 않아야 한다. | side-effect assertion |
| SRS-CAN-007 | 모든 evidence ref는 source URL 또는 query template, 시간 범위와 수집 시각을 추적할 수 있어야 한다. | provenance test |

### 5.6 후보 조회와 선택 API

```http
GET /api/v1/analyses/{analysisId}
GET /api/v1/analyses/{analysisId}/candidates
POST /api/v1/analyses/{analysisId}/selections
```

선택 request:

```json
{
  "candidateId": "candidate-id",
  "analysisVersion": 1
}
```

| ID | 소프트웨어 요구사항 | 검증 방법 |
| --- | --- | --- |
| SRS-SEL-001 | selection은 candidate ID와 analysis version을 요구해야 한다. | contract test |
| SRS-SEL-002 | candidate가 analysis에 없으면 `404 Not Found`를 반환해야 한다. | invalid candidate test |
| SRS-SEL-003 | version이 최신값과 다르면 `409 Conflict`를 반환해야 한다. | stale version test |
| SRS-SEL-004 | `ELIGIBLE`이 아니거나 evidence coverage가 불충분하면 `422`와 사람 검토 사유를 반환해야 한다. | eligibility test |
| SRS-SEL-005 | 유효한 선택은 `hotfixId`와 상태 URL을 포함한 `202 Accepted`를 반환해야 한다. | API integration test |
| SRS-SEL-006 | 동일 selection idempotency key는 같은 `hotfixId`를 반환해야 한다. | idempotency test |

### 5.7 Patch 정책과 Git 작업

| ID | 소프트웨어 요구사항 | 검증 방법 |
| --- | --- | --- |
| SRS-GIT-001 | 시스템은 선택된 hotfix별 detached worktree를 만들고 사용자의 기존 FMS working tree를 변경하지 않아야 한다. | dirty working tree test |
| SRS-GIT-002 | branch 이름은 `agent/hotfix/{analysis-or-hotfix-id}-{slug}` 형식이어야 한다. | branch naming test |
| SRS-GIT-003 | 변경 파일은 최대 10개여야 한다. | 10/11 file boundary test |
| SRS-GIT-004 | 총 added+deleted lines는 최대 500이어야 한다. | 500/501 line boundary test |
| SRS-GIT-005 | migration, secret, `.env*`, 인증서/key, `Jenkinsfile`, Kubernetes/Helm/manifest/values 파일과 `fms-deploy`는 변경할 수 없어야 한다. | forbidden path parameterized test |
| SRS-GIT-006 | policy 검사는 patch 적용 전 제안 경로와 적용 후 실제 diff에 각각 수행되어야 한다. | pre/post gate test |
| SRS-GIT-007 | policy 위반 시 branch push와 PR 생성이 수행되지 않아야 한다. | outbound interaction assertion |

### 5.8 검증과 재시도

| ID | 소프트웨어 요구사항 | 검증 방법 |
| --- | --- | --- |
| SRS-VER-001 | 시스템은 수정 반복 중 변경 module과 실패 유형에 따라 allowlist의 focused Gradle task만 실행해야 한다. | command selection test |
| SRS-VER-002 | `eu-app` Java 변경은 최소 test, architecture test와 Checkstyle을 실행해야 한다. | process adapter test |
| SRS-VER-003 | gateway와 metrics 변경은 각 module의 test 및 필수 architecture/Checkstyle task를 실행해야 한다. | module fixture test |
| SRS-VER-004 | migration이 필요한 변경은 검증을 시도하지 않고 사람 검토로 전환해야 한다. | migration diagnosis test |
| SRS-VER-005 | 검증 실패에 따른 수정 재시도는 최대 2회여야 한다. | retry boundary test |
| SRS-VER-006 | 새 실패 증거가 없으면 동일 patch 생성을 반복하지 않아야 한다. | replan guard test |
| SRS-VER-007 | 모든 검증이 통과하고 독립 review가 승인한 경우에만 PR 생성 단계로 이동해야 한다. | workflow test |
| SRS-VER-008 | Draft PR 직전에는 focused test 결과와 별개로 고정 source revision의 `eu/Jenkinsfile`에서 정의한 배포 제외 검증 단계와 동등한 `JENKINS_PR_PARITY` profile을 수정된 동일 commit에서 실행해야 한다. | pipeline profile contract test |
| SRS-VER-009 | `JENKINS_PR_PARITY`는 최소한 Jenkinsfile의 전체 Gradle verification, JaCoCo report 확인, 세 application의 local Jib image build, Docker Compose health check와 Newman suite를 포함해야 한다. | real command fixture 및 stage coverage test |
| SRS-VER-010 | `JENKINS_PR_PARITY`의 task 또는 stage를 생략하거나 실행할 수 없거나 하나라도 실패하면 시스템은 Draft PR을 생성하지 않고 `NEEDS_HUMAN_REVIEW`로 전환해야 한다. | skipped/unavailable/failed stage test |
| SRS-VER-011 | 검증 결과는 base commit, patch commit, Jenkinsfile path/hash, profile version, 실행 stage와 각 exit code를 기록해야 한다. | verification provenance serialization test |
| SRS-VER-012 | Draft PR write 직전에 현재 worktree HEAD가 성공한 `JENKINS_PR_PARITY`의 patch commit과 동일한지 검증해야 한다. | post-verification mutation test |
| SRS-VER-013 | 독립 review 이후 코드가 변경되면 기존 `JENKINS_PR_PARITY` 결과를 무효화하고 전체 profile을 다시 실행해야 한다. | review-fix invalidation test |
| SRS-VER-014 | source revision의 Jenkinsfile hash에 대응하는 승인된 parity profile이 없으면 임의 해석이나 stage 생략 없이 사람 검토로 전환해야 한다. | unknown Jenkinsfile hash test |

### 5.9 Draft PR과 CI 상태

| ID | 소프트웨어 요구사항 | 검증 방법 |
| --- | --- | --- |
| SRS-PR-001 | Bitbucket PR은 유효한 `JENKINS_PR_PARITY=SUCCESS` 결과가 있을 때만 Draft로 생성해야 한다. | Bitbucket request 및 verification gate test |
| SRS-PR-002 | PR source는 `agent/hotfix/*`, destination은 analysis에 고정된 branch여야 한다. | PR payload test |
| SRS-PR-003 | PR reviewer 목록은 비워 두어야 한다. | PR payload test |
| SRS-PR-004 | PR 본문은 analysis/source/evidence/diff policy, focused test 결과, Jenkins parity profile/hash/commit/stage 결과, BE팀과 사람 승인 필요 문구를 포함해야 한다. | body snapshot test |
| SRS-PR-005 | 동일 hotfix에 기존 branch 또는 PR이 있으면 새 PR을 생성하지 않고 기존 resource를 반환해야 한다. | recovery/idempotency test |
| SRS-PR-006 | `POST /api/v1/hotfixes/{hotfixId}/ci-status-refresh`만 Jenkins PR 상태를 조회해야 한다. | no-background-call test |
| SRS-PR-007 | Draft PR만 존재하는 상태는 `DRAFT_PR_CREATED`이며 Jenkins SUCCESS 확인 후에만 `RESOLVED`가 되어야 한다. | state transition test |

### 5.10 상태 저장과 복구

| ID | 소프트웨어 요구사항 | 검증 방법 |
| --- | --- | --- |
| SRS-STA-001 | analysis, hotfix, interpretation과 execution 상태는 Langfuse와 같은 PostgreSQL 인스턴스의 `hotfix_agent` 스키마에 저장해야 한다. | Compose 및 persistence integration test |
| SRS-STA-002 | agent 테이블과 Liquibase 메타데이터는 Langfuse가 사용하는 `public` 스키마와 분리해야 한다. | schema inspection test |
| SRS-STA-003 | 반복 값은 안정적인 순서 컬럼을 가진 관계형 자식 테이블로 저장하고 JSON/JSONB payload 컬럼을 사용하지 않아야 한다. | migration 및 mapping test |
| SRS-STA-004 | 재시작 복구 시 외부 write 전에 기존 branch와 PR을 조회해야 한다. | crash recovery test |
| SRS-STA-005 | analysis는 기본 24시간 후 선택 불가 상태가 되어야 한다. | clock test |
| SRS-STA-006 | Jenkins·관측 분석 원문은 관계형 컬럼에 저장하고, 재기동 시 `ANALYSIS_REQUESTED` 또는 `ANALYZING` 작업을 동일 analysis로 다시 제출해야 한다. | JPA mapping 및 analysis recovery test |
| SRS-STA-007 | 재기동 시 `SELECTED`, `PATCHING`, `VERIFYING` hotfix는 고정 소스 commit과 동일 hotfix ID로 현재 로컬 workflow를 안전하게 재실행해야 한다. | hotfix recovery 및 worktree recreation test |
| SRS-STA-008 | `DRAFT_PR_CREATED` hotfix는 재기동으로 로컬 workflow나 새 PR을 시작하지 않으며, Jenkins 상태는 명시적 CI refresh에서만 갱신해야 한다. | hotfix recovery no-interaction test |

### 5.11 자연어 명령 API와 가드레일

지원 intent는 `ANALYZE_JENKINS`, `ANALYZE_OBSERVABILITY`, `LIST_CANDIDATES`,
`SELECT_CANDIDATE`, `GET_HOTFIX_STATUS`, `REFRESH_CI_STATUS`로 폐쇄한다.

| ID | 소프트웨어 요구사항 | 검증 방법 |
| --- | --- | --- |
| SRS-NL-001 | `POST /api/v1/natural-language/interpretations`는 최대 2,000자의 한국어 또는 영어 `text` 하나만 받아야 한다. | length/unknown field contract test |
| SRS-NL-002 | 해석 단계는 외부 시스템, source, worktree, Git, verification과 PR tool을 호출하지 않아야 한다. | zero-interaction test |
| SRS-NL-003 | 해석 결과는 폐쇄된 intent, typed parameters, 누락·모호 필드, 정책 효과, interpretation version, command hash와 만료 시각을 포함해야 한다. | schema test |
| SRS-NL-004 | 필수 parameter가 누락되거나 둘 이상의 유효한 해석이 있으면 상태를 `NEEDS_CLARIFICATION`으로 만들고 실행 가능 hash를 발급하지 않아야 한다. | ambiguous fixture test |
| SRS-NL-005 | unsupported intent, 임의 shell/URL/query 실행, 정책 변경, merge/tag/deploy 요청은 `REJECTED`와 안정적인 reason code로 반환해야 한다. | adversarial fixture test |
| SRS-NL-006 | 실행 가능한 해석은 `READY_FOR_CONFIRMATION`이어야 하며 해석만으로 기존 use case를 실행하지 않아야 한다. | no-side-effect test |
| SRS-NL-007 | `POST /api/v1/natural-language/interpretations/{id}/executions`는 interpretation version과 command hash의 명시적 확인을 요구해야 한다. | missing/mismatch test |
| SRS-NL-008 | interpretation은 10분 후 만료되어야 하며 만료, version/hash mismatch 또는 이미 대체된 해석은 `409`로 거부해야 한다. | clock/concurrency test |
| SRS-NL-009 | 실행 service는 LLM output이나 원문 text가 아니라 검증된 typed command만 기존 구조화 application use case에 전달해야 한다. | architecture/argument capture test |
| SRS-NL-010 | 자연어 `SELECT_CANDIDATE`는 analysis ID, candidate ID와 analysis version이 모두 명시된 경우에만 실행 가능해야 한다. | omitted identifier test |
| SRS-NL-011 | 자연어 observability 요청은 start/end/environment/source를 모두 요구하고 service와 query scope는 환경변수로 설정된 대상 서비스 정책으로 고정해야 한다. | normalization test |
| SRS-NL-012 | 자연어로 시작한 실행은 `SRS-SRC`, `SRS-SEL`, `SRS-GIT`, `SRS-VER`, `SRS-PR` gate를 우회하거나 완화하지 않아야 한다. | parity workflow test |
| SRS-NL-013 | 자연어 원문은 application log, PR과 LLM observability에 기록하지 않고 redacted preview와 SHA-256만 상태에 저장해야 한다. | leakage test |
| SRS-NL-014 | 동일 interpretation execution idempotency key는 같은 delegated resource를 반환하고 중복 실행하지 않아야 한다. | replay test |
| SRS-NL-015 | 자연어 해석은 DB에 작업 상태를 저장하고 HTTP 연결 종료나 서버 재기동 후에도 복구되어야 한다. | persistence/recovery test |

### 5.12 운영 UI

| ID | 소프트웨어 요구사항 | 검증 방법 |
| --- | --- | --- |
| SRS-UI-001 | `GET /`는 JavaScript로 화면 전체를 조립하지 않는 SSR shell을 반환하고 기존 `GET /ui`는 `/`로 redirect해야 한다. | controller 및 browser test |
| SRS-UI-002 | 실패 PR fragment는 Jenkins의 마지막 build가 `FAILURE`인 `PR-*` job과 대응하는 open Bitbucket PR의 branch·commit·링크만 표시해야 한다. | Jenkins/Bitbucket adapter test |
| SRS-UI-003 | 실패 PR 목록은 화면 진입 1회와 사용자의 명시적 새로고침에서만 외부 시스템을 조회해야 한다. | template trigger 검증 |
| SRS-UI-004 | 관측 fragment는 사용자가 입력한 시작·종료 시각과 `DEV`, `QA`, `PROD` 환경을 요구하고 환경변수로 설정된 대상 서비스 범위를 서버에서 고정해야 한다. | controller/adapter test |
| SRS-UI-005 | 관측 목록은 활성 알람과 오류 Trace를 구분하고 각 Grafana 상세 링크를 제공해야 한다. | Grafana adapter 및 template test |
| SRS-UI-006 | 자연어 UI는 해석 미리보기와 version/hash 확인 실행을 분리하고 기존 자연어 use case만 호출해야 한다. | controller/module adapter test |
| SRS-UI-007 | Draft PR 버튼은 완료된 분석의 `ELIGIBLE` 후보에만 노출되고 analysis version과 candidate ID를 기존 selection use case에 전달해야 한다. | controller/template test |
| SRS-UI-008 | hotfix 진행 화면은 PostgreSQL의 agent 상태만 주기적으로 읽고 Jenkins/Grafana를 자동 polling하지 않아야 한다. | adapter interaction 및 template trigger 검증 |
| SRS-UI-009 | 진행 화면은 `SELECTED`, `PATCHING`, `VERIFYING`, `DRAFT_PR_CREATED`, `RESOLVED`와 사람 검토/실패 상태 및 가용한 외부 링크를 표시해야 한다. | assembler 및 template test |
| SRS-UI-010 | 진행 화면은 같은 `analysisId`의 분석과 hotfix를 하나의 카드로 결합하고 분석, Draft PR 생성, 로컬 빌드·테스트, Jenkins CI의 네 단계를 표시해야 한다. | assembler, controller 및 SSR fragment 검증 |
| SRS-UI-011 | 하나의 분석에 여러 원인이 있으면 UI와 상태 결합은 각 `candidateId`의 hotfix, branch, Draft PR 및 CI를 독립적으로 추적해야 한다. | workflow assembler test |
| SRS-UI-012 | UI는 로컬 작업 취소·삭제, 실패 작업의 전체 guardrail 재시작, 명시적 CI 갱신과 Bitbucket/Jenkins 링크를 제공하고 로컬 삭제로 외부 기록을 삭제하지 않아야 한다. | controller, management service 및 workflow cancellation test |
| SRS-UI-013 | UI는 실행 중인 세부 단계와 설명, 실패 단계·코드·복구 안내, 각 검증의 종료 코드와 redaction된 출력 요약을 DB에서 복원해 표시해야 한다. | assembler, persistence mapping 및 SSR fragment 검증 |
| SRS-UI-014 | branch가 생성된 실패 hotfix는 기존 `agent/hotfix/*` branch를 사람 검토용으로 게시하고 Bitbucket 링크와 로컬 수정 절차를 표시해야 한다. | workspace adapter, controller 및 template test |
| SRS-UI-015 | 대화형 UI는 직전 실패 PR 또는 정밀분석 우선순위 결과의 참조를 유지하여 후속 대명사·순번 요청을 같은 대상으로 해석해야 한다. | controller conversation test |
| SRS-UI-016 | 대화형 UI는 최근 미진행 후보의 시급 작업과 정밀분석 필요 후보를 우선순위로 안내하되 후보 선택 안전 gate를 우회하지 않아야 한다. | priority resolver/controller test |
| SRS-UI-017 | 사용자에게 표시되는 build, 관측 신호와 작업 시각은 KST로 표현해야 한다. | view/template test |
| SRS-UI-015 | 사람이 같은 branch에 push한 commit의 재검증은 기준 commit 계보와 변경 정책을 다시 확인하고 집중 테스트, AI review, Jenkins parity를 모두 통과한 경우에만 Draft PR을 게시해야 한다. | guarded workflow 및 local Git integration test |
| SRS-UI-016 | AI 분석 요청은 기존 workflow 목록을 비우지 않고 해당 PR 버튼에서 진행 상태를 표시하며, 완료 시 해당 analysis 카드 하나만 목록 맨 위에 갱신해야 한다. | controller, HTMX fragment 및 JavaScript behavior test |
| SRS-UI-017 | 완료된 동일 분석 요청은 새 분석을 암묵적으로 만들지 않고 요청 버튼을 `중복 요청 재분석`으로 전환하여 명시적인 강제 재분석만 허용해야 한다. | controller 및 action fragment test |

## 6. 데이터 요구사항

### 6.1 AnalysisSession

| Field | Type | 필수 | 설명 |
| --- | --- | --- | --- |
| `identity.analysisId` | string | Y | 분석 식별자 |
| `identity.version` | long | Y | 선택 동시성 제어 |
| `identity.requestHash` | string | Y | idempotency request fingerprint |
| `snapshot.source` | tagged union | Y | branch 또는 open PR |
| `snapshot.sourceRevision` | object | Y | commit, destination, provenance |
| `snapshot.createdAt` | instant | Y | 생성 시각 |
| `snapshot.expiresAt` | instant | Y | 선택 만료 시각 |
| `result.status` | enum | Y | 분석 상태 |
| `result.candidates` | array | Y | 후보 목록, 없으면 empty |
| `result.failureReason` | nullable string | N | 실패 또는 사람 검토 사유 |

### 6.2 상태 값

```text
ANALYSIS_REQUESTED -> ANALYZING -> CANDIDATES_READY
                               -> NEEDS_HUMAN_REVIEW | FAILED

SELECTED -> PATCHING -> VERIFYING -> DRAFT_PR_CREATED
          |            |          -> NEEDS_HUMAN_REVIEW | FAILED

DRAFT_PR_CREATED -> RESOLVED
```

허용되지 않은 상태 전이는 `409 Conflict`로 거부해야 한다.

## 7. 외부 인터페이스 요구사항

### 7.1 Jenkins

- Base URL: `JENKINS_BASE_URL`
- 인증: username + API token
- TLS: `JENKINS_TLS_VERIFY=false` 시 시연 환경에서만 인증서 검증 생략
- 허용 메서드: build/job metadata, console, test report 조회를 위한 GET
- 금지: build trigger, cancel, job configure

### 7.2 Grafana

- Base URL: `GRAFANA_BASE_URL`
- 인증: read-only service account token
- TLS: `GRAFANA_TLS_VERIFY=false` 시 시연 환경에서 인증서 검증 생략
- Prometheus UID: `prometheus`
- Tempo UID: `tempo`
- Loki UID: `P8E80F9AEF21F6940`
- 공통 namespace label: `namespace=fms-eu-{dev|qa|prod}`
- 공통 application label: `service_name=fms-eu-{env}-app`
- 허용: datasource proxy read query
- 금지: dashboard, datasource, Alert rule, contact point write

### 7.3 Bitbucket

- Repository: `autocrypt/fms`
- 인증: Bearer access token
- 허용: repository/branch/PR read, hotfix branch push, Draft PR create/read
- 금지: approve, merge, tag, release

### 7.4 LiteLLM/Embabel

- LLM endpoint와 key는 환경변수로 주입해야 한다.
- triage, reasoning, review 모델은 역할별 설정을 지원해야 한다.
- 미설정 역할은 `LITELLM_MODEL`로 fallback해야 한다.
- 모든 LLM 응답은 typed DTO schema를 통과해야 한다.
- schema 실패는 자동 재요청하지 않고 이후 사람 검토로 전환해야 한다.

## 8. 비기능 요구사항

### 8.1 보안과 개인정보

| ID | 요구사항 |
| --- | --- |
| SRS-NFR-SEC-001 | API는 기본적으로 loopback interface에만 bind해야 한다. |
| SRS-NFR-SEC-002 | token과 password는 source, log, agent 상태 테이블과 PR 본문에 기록하지 않아야 한다. |
| SRS-NFR-SEC-003 | Authorization, Cookie, secret, connection string과 식별정보는 LLM 전달 전에 마스킹해야 한다. |
| SRS-NFR-SEC-004 | 원본 운영 evidence는 Git에 포함하지 않아야 한다. |
| SRS-NFR-SEC-005 | Embabel observability에서 prompt/result content capture를 비활성화해야 한다. |
| SRS-NFR-SEC-006 | TLS verify 비활성화는 Jenkins와 Grafana client별 명시적 설정으로 한정해야 한다. |

### 8.2 신뢰성과 일관성

| ID | 요구사항 |
| --- | --- |
| SRS-NFR-REL-001 | 모든 외부 write는 deterministic idempotency key를 사용해야 한다. |
| SRS-NFR-REL-002 | 외부 write 직전에 policy와 source freshness를 다시 검증해야 한다. |
| SRS-NFR-REL-003 | 외부 API 오류는 상태와 마스킹된 오류 사유로 저장되어야 한다. |
| SRS-NFR-REL-004 | 후보가 없거나 안전하지 않은 사건은 실패한 patch 대신 구조화된 사람 검토 결과를 제공해야 한다. |

### 8.3 성능과 자원 제한

| ID | 요구사항 |
| --- | --- |
| SRS-NFR-PER-001 | 정상 로컬 자원 상태에서 분석 및 선택 API는 작업을 비동기로 접수하고 2초 이내 `202`를 반환해야 한다. |
| SRS-NFR-PER-002 | 외부 query는 SRS-OBS-007~009의 결과 한도를 적용해야 한다. |
| SRS-NFR-PER-003 | LLM 입력은 설정된 token budget을 넘지 않도록 중요도 순으로 축약해야 한다. |
| SRS-NFR-PER-004 | 한 사고 처리의 의미상 LLM 호출은 triage 1회, 후보 분석 1회, patch 생성 최대 3회, review 1회로 제한해야 한다. |
| SRS-NFR-PER-005 | 역할별 입력 상한은 triage 8,000, reasoning 16,000, review 8,000 token이며 typed 사고 처리의 입력 예약량 합계는 최대 80,000 token이어야 한다. 자연어 해석 경로는 별도 triage 1회로 전체 최대 88,000 token이어야 한다. |
| SRS-NFR-PER-006 | 역할별 출력 상한을 요청에 명시하고 provider 전송과 구조화 출력 binding은 의미상 호출마다 한 번만 시도해야 한다. |
| SRS-NFR-PER-007 | prompt와 completion 본문을 저장하지 않고 Spring AI token usage metric을 Prometheus endpoint로 제공해야 한다. |

### 8.4 유지보수성과 아키텍처

| ID | 요구사항 |
| --- | --- |
| SRS-NFR-MNT-001 | 기능은 `incident`, `command`, `orchestrator`, `global` package로 분리하고 각 기능의 adapter/application/domain 경계를 유지해야 한다. |
| SRS-NFR-MNT-002 | slice 간 연결은 inbound/outbound port를 통해야 한다. |
| SRS-NFR-MNT-003 | 기존 architecture, Checkstyle과 test task를 통과해야 한다. |
| SRS-NFR-MNT-004 | 외부 REST adapter는 fixture 기반 contract test를 가져야 한다. |
| SRS-NFR-MNT-005 | 실제 credential smoke test는 기본 `check`와 분리해야 한다. |
| SRS-NFR-MNT-006 | 각 Embabel agent/subagent의 capability manifest는 직접 할당된 skill 최대 5개와 tool 최대 5개만 허용해야 한다. |
| SRS-NFR-MNT-007 | 기능 구현에 skill 또는 tool이 5개를 초과하여 필요하면 typed input/output 경계를 가진 하위 에이전트로 분리해야 한다. |
| SRS-NFR-MNT-008 | 부모 에이전트의 직접 capability 수에는 자식의 capability를 합산하지 않지만 부모는 자식 전용 tool을 직접 호출할 수 없고 typed artifact로만 결과를 받아야 한다. |
| SRS-NFR-MNT-009 | architecture test는 모든 agent capability manifest의 중복 ID, 최대 개수와 부모-자식 tool 소유권을 검증해야 한다. |

## 9. 오류 응답 요구사항

오류 응답은 secret을 포함하지 않는 공통 구조를 사용해야 한다.

```json
{
  "code": "STALE_ANALYSIS",
  "message": "Analysis source has changed. Run analysis again.",
  "resourceId": "analysis-id"
}
```

| HTTP status | 적용 사례 |
| --- | --- |
| `400` | validation, unknown/금지 field, idempotency key 누락 |
| `404` | analysis, candidate, hotfix, branch 또는 PR 없음 |
| `409` | idempotency 충돌, stale version/source, 잘못된 상태 전이 |
| `422` | 성공 Jenkins build, 선택 불가 candidate, 정책상 자동 수정 불가, Jenkins 동등 검증 실행 불가 |
| `502` | Jenkins/Grafana/Bitbucket/LiteLLM 응답 오류 |
| `503` | 외부 시스템 일시 불가 또는 local worker 실행 불가 |

## 10. CRS 추적성

| CRS 요구사항 | SRS 요구사항 |
| --- | --- |
| CRS-FUN-001 | SRS-API-006, SRS-PR-006 |
| CRS-FUN-002 | SRS-JEN-001~005 |
| CRS-FUN-003~005 | SRS-OBS-001~006 |
| CRS-FUN-006 | SRS-SRC-001~006 |
| CRS-FUN-007~008 | SRS-CAN-002~005, SRS-CAN-007 |
| CRS-FUN-009~011 | SRS-CAN-006, SRS-SEL-001~006, SRS-SRC-006 |
| CRS-FUN-012~015 | SRS-SRC-003~007, SRS-GIT-001~002, SRS-PR-002 |
| CRS-FUN-016~019 | SRS-VER-001~014, SRS-PR-001~007 |
| CRS-FUN-020~023 | SRS-NL-001~014 |
| CRS-SAF-001 | SRS-JEN-001, SRS-OBS-011, 7절 외부 인터페이스 |
| CRS-SAF-002~004 | SRS-GIT-003~006, SRS-VER-005 |
| CRS-SAF-005 | SRS-CAN-005, SRS-VER-004, SRS-NFR-REL-004 |
| CRS-SAF-006 | SRS-NFR-SEC-002~005 |
| CRS-SAF-007 | SRS-OBS-005~006, SRS-VER-001 |
| CRS-SAF-008 | 4.2절, 7절 금지 인터페이스 |
| CRS-SAF-009 | SRS-VER-008~014, SRS-PR-001 |
| CRS-SAF-010~012 | SRS-NL-002, SRS-NL-005~013 |
| CRS-QUA-001 | SRS-API-001~003, SRS-SEL-006, SRS-PR-005 |
| CRS-QUA-002 | SRS-CAN-007, SRS-SRC-005, SRS-PR-004 |
| CRS-QUA-003~004 | SRS-API-005, SRS-STA-001~005 |
| CRS-QUA-005 | SRS-GIT-001 |
| CRS-QUA-006 | 4.2절, SRS-NFR-REL-001~002 |
| CRS-QUA-007 | SRS-NFR-MNT-006~009 |

## 11. 시스템 인수 테스트

| Test ID | 사전 조건 | 실행 | 기대 결과 |
| --- | --- | --- | --- |
| SAT-001 | 외부 adapter mock 준비 | API 호출 없이 시간 경과 | 외부 호출 0회 |
| SAT-002 | 실패 Jenkins fixture와 branch 준비 | Jenkins analysis POST | `202`, 완료 후 versioned 후보 목록, Git write 0회 |
| SAT-003 | 혼합 service 관측 fixture 준비 | `PROD`, 30분 범위로 observability POST | 지정 범위의 prod `eu-app` evidence만 사용 |
| SAT-004 | candidates ready | 선택 없이 상태 조회 | branch/worktree/PR 없음 |
| SAT-005 | eligible candidate와 최신 version | selection POST | `202`, 동일 patch commit의 `JENKINS_PR_PARITY` 전체 성공 후 지정 destination의 Draft PR |
| SAT-006 | analysis 이후 source commit 이동 | selection POST | `409`, write 없음 |
| SAT-007 | 11개 파일 또는 501 lines patch | selection 실행 | policy 거부, PR 없음 |
| SAT-008 | 금지 경로 patch | selection 실행 | 사람 검토 상태, PR 없음 |
| SAT-009 | 동일 idempotency key | analysis/selection 반복 | 같은 ID, branch/PR 1개 |
| SAT-010 | Draft PR과 Jenkins SUCCESS fixture | CI refresh POST | `RESOLVED`와 build URL 반환 |
| SAT-011 | focused test 성공, parity stage 실패 또는 실행 불가 | selection 실행 | `NEEDS_HUMAN_REVIEW`, Draft PR 없음, 실패 stage 반환 |
| SAT-012 | parity 성공 후 worktree HEAD 변경 | PR publish 시도 | 기존 결과 무효화, parity 재실행 전 PR 없음 |
| SAT-013 | agent manifest에 skill 또는 tool 6개 할당 | architecture test 실행 | 위반 agent 이름과 capability 목록을 표시하며 실패 |

## 12. 구현 및 실환경 확인 상태

| ID | 항목 | 완료 조건 |
| --- | --- | --- |
| TBD-001 | Grafana read-only token | 완료: datasource 및 query API GET 성공 |
| TBD-002 | Loki datasource UID | 완료: `P8E80F9AEF21F6940` |
| TBD-003 | 환경별 `eu-app` label mapping | 완료: namespace와 `service_name` 공통 매핑 확인 |
| TBD-004 | Shadow fixture | 완료: FMS PR #1292 → parity/Newman 통과 → Draft PR #1295 |

2026-08-21 shadow 검증에서 hotfix commit `d57a84a470878933ef23f370a01b034052394653`의 네 parity
stage와 Newman 20/20 성공, reviewer 없는 Draft PR 생성을 확인했다. PR #1295 Jenkins build는 시작
상태까지 확인했으며 최종 SUCCESS는 별도 CI refresh로 확인해야 한다.
