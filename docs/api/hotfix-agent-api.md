# Hotfix Agent API 설계

## 1. 문서 목적

이 문서는 [SRS](../requirements/SRS.md)의 API 요구사항을 HTTP 계약으로 구체화한다. 구현 중 API
필드나 상태 코드가 바뀌면 먼저 SRS 요구사항 ID의 변경 필요성을 검토하고 이 문서를 함께 수정한다.

## 2. 공통 계약

- Base path: `/api/v1`
- Content type: `application/json`
- 분석과 선택은 자동 polling 없이 사용자의 API 호출로만 시작한다.
- 모든 `POST` 분석/선택/자연어 해석·실행 요청은 `Idempotency-Key` header를 요구한다.
- unknown JSON field는 무시하지 않고 `400 Bad Request`로 거부한다.
- 비동기 작업 접수는 `202 Accepted`와 resource 상태 URL을 반환한다.
- 서비스는 기본적으로 `127.0.0.1`에 bind한다.

공통 비동기 접수 응답:

```json
{
  "resourceId": "01K...",
  "status": "ANALYSIS_REQUESTED",
  "statusUrl": "/api/v1/analyses/01K..."
}
```

공통 오류 응답:

```json
{
  "code": "STALE_ANALYSIS",
  "message": "Analysis source has changed. Run analysis again.",
  "resourceId": "01K..."
}
```

관련 요구사항: `SRS-API-001~006`, `SRS-NFR-SEC-001~005`, SRS 9절.

## 3. 공통 Source 모델

`source`는 discriminator `type`을 사용하는 tagged union이다.

Branch source:

```json
{
  "type": "BRANCH",
  "branchName": "main"
}
```

Pull request source:

```json
{
  "type": "PULL_REQUEST",
  "pullRequestId": 1285
}
```

Branch는 원격 head를, PR은 open PR의 source commit을 분석 base로 고정한다. 생성되는 Draft PR의
destination은 각각 입력 branch와 원본 PR source branch다. source branch에는 직접 push하지 않는다.

관련 요구사항: `SRS-SRC-001~007`.

## 4. Endpoint 요약과 SRS 매핑

| Method | Path | 목적 | 직접 매핑되는 SRS |
| --- | --- | --- | --- |
| `POST` | `/analyses/jenkins` | 지정 Jenkins 실패 build 분석 접수 | `SRS-API-001~006`, `SRS-JEN-001~005`, `SRS-SRC-001~007` |
| `POST` | `/analyses/observability` | 지정 환경·시간 범위 관측 분석 접수 | `SRS-API-001~006`, `SRS-OBS-001~011`, `SRS-SRC-001~007` |
| `GET` | `/analyses/{analysisId}` | 분석 상태와 요약 조회 | `SRS-CAN-002~007`, `SRS-STA-001~005` |
| `GET` | `/analyses/{analysisId}/candidates` | 버전이 있는 버그 후보 목록 조회 | `SRS-CAN-002~007` |
| `POST` | `/analyses/{analysisId}/selections` | 사용자가 고른 후보의 hotfix 접수 | `SRS-SEL-001~006`, `SRS-SRC-006~007`, `SRS-GIT-001~007`, `SRS-VER-001~014` |
| `GET` | `/hotfixes/{hotfixId}` | patch, Jenkins 동등 검증, Draft PR 진행 상태 조회 | `SRS-VER-001~014`, `SRS-PR-001~005`, `SRS-STA-001~005` |
| `POST` | `/hotfixes/{hotfixId}/ci-status-refresh` | 해당 Draft PR Jenkins 상태 1회 갱신 | `SRS-PR-006~007` |
| `POST` | `/natural-language/interpretations` | 자연어를 실행 전 typed command로 해석 | `SRS-NL-001~006`, `SRS-NL-013` |
| `GET` | `/natural-language/interpretations/{interpretationId}` | 해석, 명확화 질문과 만료 상태 조회 | `SRS-NL-003~006`, `SRS-NL-008` |
| `POST` | `/natural-language/interpretations/{interpretationId}/executions` | 사용자가 확인한 command를 기존 use case로 실행 | `SRS-NL-007~014` |

## 5. Jenkins 분석

```http
POST /api/v1/analyses/jenkins
Idempotency-Key: jenkins-fms-eu-main-181
Content-Type: application/json
```

```json
{
  "jobPath": "FMS-EU/job/main",
  "buildNumber": 181,
  "source": {
    "type": "BRANCH",
    "branchName": "main"
  }
}
```

| Field | Type | 검증 |
| --- | --- | --- |
| `jobPath` | string | `FMS-EU` root 하위 job만 허용 |
| `buildNumber` | positive integer | 존재하고 완료된 실패 build |
| `source` | `BranchSource \| PullRequestSource` | 3절 source resolution 적용 |

성공 응답은 `202 Accepted`다. 실패 상태가 아닌 build는 `422`, build revision과 source commit 불일치는
`409`로 반환한다. 이 호출은 Jenkins metadata, console, test report를 읽을 수 있지만 build를
trigger하거나 중단하지 않는다.

관련 요구사항: `SRS-JEN-001~005`, `SRS-SRC-001~007`, `SRS-NFR-PER-001`.

## 6. Grafana 관측 분석

```http
POST /api/v1/analyses/observability
Idempotency-Key: obs-prod-20260820T125000-131000
Content-Type: application/json
```

```json
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

| Field | Type | 검증 |
| --- | --- | --- |
| `startAt` | offset datetime | timezone offset 필수, `endAt`보다 이전 |
| `endAt` | offset datetime | timezone offset 필수, 범위 최대 60분 |
| `environment` | enum | `DEV`, `QA`, `PROD` |
| `source` | `BranchSource \| PullRequestSource` | 3절 source resolution 적용 |

서버가 환경을 `fms-eu-{env}`로 변환하고 대상 service를 `fms-eu-{env}-app`으로 고정한다.
`service`, `namespace`, `promql`, `logql`, `traceql`, `observedAt`, `windowMinutes` 필드는 입력받지
않으며 포함되면 `400`이다. 쿼리는 서버 allowlist template만 사용한다.

관련 요구사항: `SRS-OBS-001~011`, `SRS-NFR-PER-002~003`.

## 7. 분석 및 후보 조회

```http
GET /api/v1/analyses/{analysisId}
GET /api/v1/analyses/{analysisId}/candidates
```

분석 상태 응답:

```json
{
  "analysisId": "01K...",
  "version": 1,
  "status": "CANDIDATES_READY",
  "sourceRevision": {
    "commit": "abc123",
    "destinationBranch": "main"
  },
  "candidateCount": 2,
  "createdAt": "2026-08-20T04:00:00Z",
  "expiresAt": "2026-08-21T04:00:00Z"
}
```

후보 목록의 각 항목은 최소한 `candidateId`, `title`, `rootCause`, `confidence`, `eligibility`,
`sourceLocations`, `evidenceRefs`, `counterEvidence`, `fixSummary`, `verificationSummary`를 가진다.
조회는 read-only이며 worktree, branch, patch 또는 PR을 만들지 않는다.

관련 요구사항: `SRS-CAN-001~007`, `SRS-STA-001~005`.

## 8. 후보 선택과 hotfix 접수

```http
POST /api/v1/analyses/{analysisId}/selections
Idempotency-Key: select-01K-analysis-01K-candidate
Content-Type: application/json
```

```json
{
  "candidateId": "01K-candidate",
  "analysisVersion": 1
}
```

접수 전 다음 조건을 모두 다시 검증한다.

1. candidate가 해당 analysis에 속한다.
2. analysis version과 source commit이 최신이다.
3. candidate 상태가 `ELIGIBLE`이다.
4. evidence coverage가 patch 생성을 허용한다.
5. 동일 selection의 기존 hotfix가 없다.
6. 실행 모드가 `DRAFT_PR`이다.

성공 시:

```json
{
  "hotfixId": "01K-hotfix",
  "status": "SELECTED",
  "statusUrl": "/api/v1/hotfixes/01K-hotfix"
}
```

candidate 없음은 `404`, stale version/source는 `409`, 선택 불가 또는 `REPORT_ONLY` 정책은 `422`다.

관련 요구사항: `SRS-SEL-001~006`, `SRS-SRC-006~007`, `SRS-GIT-001~007`,
`SRS-NFR-REL-001~004`.

## 9. Hotfix 상태 조회

```http
GET /api/v1/hotfixes/{hotfixId}
```

```json
{
  "identity": {
    "hotfixId": "01K-hotfix",
    "analysisId": "01K-analysis",
    "candidateId": "01K-candidate"
  },
  "progress": {
    "status": "DRAFT_PR_CREATED",
    "branchName": "agent/hotfix/01K-hotfix-null-check",
    "changedFiles": 2,
    "changedLines": 34,
    "verification": {
      "focusedAttempts": 1,
      "provenance": {
        "baseCommit": "abc123",
        "patchCommit": "def456",
        "jenkinsfile": {
          "path": "eu/Jenkinsfile",
          "sha256": "sha256-value",
          "profileVersion": 1
        }
      },
      "stages": [
        {"name": "jenkins-gradle-verification", "exitCode": 0, "required": true},
        {"name": "jenkins-coverage-report", "exitCode": 0, "required": true},
        {"name": "jenkins-image-build", "exitCode": 0, "required": true},
        {"name": "jenkins-integration-test", "exitCode": 0, "required": true}
      ]
    },
    "humanReviewReason": null
  },
  "publication": {
    "draftPullRequestUrl": "https://bitbucket.org/autocrypt/fms/pull-requests/1290",
    "ciBuildUrl": "https://jenkins.autocrypt-fms.io/job/FMS-EU/job/PR-1290/",
    "ciResult": "PENDING"
  }
}
```

모든 required stage의 `exitCode=0`, 검증 patch commit과 현재 worktree HEAD 일치가
확인되기 전에는 `draftPullRequestUrl`이 생성될 수 없다. focused test만 성공한 상태도 PR 생성 조건을
충족하지 않는다. parity stage 실패·skip·실행 불가는 `NEEDS_HUMAN_REVIEW`와 단계별 사유를 반환한다.

상태는 SRS 6.2절 전이만 허용한다. `DRAFT_PR_CREATED`는 해결 완료가 아니다.

관련 요구사항: `SRS-VER-001~014`, `SRS-PR-001~005`, `SRS-STA-001~005`.

## 10. CI 상태 수동 갱신

```http
POST /api/v1/hotfixes/{hotfixId}/ci-status-refresh
```

이 API만 해당 Draft PR의 Jenkins 상태를 한 번 조회한다. background polling은 두지 않는다.
Jenkins 결과가 `SUCCESS`면 `RESOLVED`, 그 외에는 현재 상태와 build URL을 `200 OK`로 반환한다.
이 endpoint는 Jenkins build를 trigger하지 않는다.

관련 요구사항: `SRS-API-006`, `SRS-PR-006~007`.

## 11. 자연어 명령 해석과 실행

자연어 API는 chat session이나 범용 명령 실행기가 아니다. 각 요청은 독립적이며 다음 두 단계로
처리한다.

```text
natural-language text
  -> closed intent + typed parameters
  -> policy preview / clarification / rejection
  -> explicit version + command hash confirmation
  -> existing structured application use case
```

### 11.1 해석 생성

```http
POST /api/v1/natural-language/interpretations
Idempotency-Key: nl-jenkins-main-181
Content-Type: application/json
```

```json
{
  "text": "FMS-EU main 181번 실패 빌드를 main 브랜치 기준으로 분석해줘"
}
```

`text`는 최대 2,000자이며 unknown field는 거부한다. 해석 단계에서는 Jenkins, Grafana, Bitbucket,
source, filesystem과 Git을 호출하지 않는다. 실행 가능한 응답 예시는 다음과 같다.

```json
{
  "interpretationId": "01K-interpretation",
  "version": 1,
  "status": "READY_FOR_CONFIRMATION",
  "intent": "ANALYZE_JENKINS",
  "parameters": {
    "jobPath": "FMS-EU/job/main",
    "buildNumber": 181,
    "source": {
      "type": "BRANCH",
      "branchName": "main"
    }
  },
  "policyPreview": {
    "repository": "autocrypt/fms",
    "delivery": "DRAFT_PR_ONLY",
    "requiresCandidateSelection": true
  },
  "commandHash": "sha256:...",
  "expiresAt": "2026-08-20T06:10:00Z"
}
```

지원 intent는 다음 여섯 개로 한정한다.

| Intent | 필수 typed parameter | 실행 대상 |
| --- | --- | --- |
| `ANALYZE_JENKINS` | job path, build number, source | Jenkins 분석 use case |
| `ANALYZE_OBSERVABILITY` | start/end, environment, source | Observability 분석 use case |
| `LIST_CANDIDATES` | analysis ID | 후보 조회 use case |
| `SELECT_CANDIDATE` | analysis ID/version, candidate ID | 선택 use case |
| `GET_HOTFIX_STATUS` | hotfix ID | 상태 조회 use case |
| `REFRESH_CI_STATUS` | hotfix ID | CI 수동 갱신 use case |

필수값이 없거나 둘 이상의 의미가 가능하면 `NEEDS_CLARIFICATION`과 `missingFields`,
`clarificationQuestions`를 반환하며 `commandHash`는 반환하지 않는다. 지원하지 않는 tool 실행,
raw query, 정책 변경, merge/tag/deploy 요청은 `REJECTED`와 reason code를 반환한다.

### 11.2 확인 후 실행

```http
POST /api/v1/natural-language/interpretations/01K-interpretation/executions
Idempotency-Key: execute-01K-interpretation-v1
Content-Type: application/json
```

```json
{
  "interpretationVersion": 1,
  "commandHash": "sha256:..."
}
```

서버는 hash를 다시 계산하고 10분 만료, version, idempotency와 현재 정책을 검증한다. 그 후 원문이나
LLM output이 아니라 typed command를 기존 구조화 use case에 전달한다. 따라서 자연어 실행도 후보
선택 전 write 금지, source freshness, 변경 한도, 금지 파일, Jenkins parity와 Draft-only 정책을 그대로
적용한다. 실행 응답은 위임된 analysis/hotfix resource ID와 상태 URL을 반환한다.

상세 위협 모델과 guard 순서는 [자연어 API 가드레일](../design/natural-language-api-guardrails.md)을
정본으로 사용한다.

관련 요구사항: `SRS-NL-001~014`, `SRS-API-001~006`, 기존 intent별 SRS.

## 12. 상태 코드 결정표

| Status | 조건 | 대표 SRS |
| --- | --- | --- |
| `200 OK` | 상태와 후보 조회 | `SRS-CAN-002~007` |
| `202 Accepted` | 새 분석/selection 접수 또는 동일 idempotent 비동기 resource 재응답 | `SRS-API-002`, `SRS-API-005`, `SRS-SEL-005` |
| `400 Bad Request` | validation, unknown field, idempotency key 누락 | `SRS-API-001`, `SRS-API-004`, `SRS-OBS-005` |
| `404 Not Found` | analysis, candidate, hotfix, branch 또는 PR 없음 | `SRS-SEL-002`, `SRS-SRC-001~002` |
| `409 Conflict` | idempotency 충돌, stale source/version, 잘못된 상태 전이 | `SRS-API-003`, `SRS-SRC-006`, `SRS-SEL-003` |
| `422 Unprocessable Entity` | 성공 build, 선택 불가, 정책상 자동 수정 불가, unsupported/rejected 자연어, Jenkins parity 실행 불가 | `SRS-JEN-002`, `SRS-SEL-004`, `SRS-NL-005`, `SRS-GIT-003~007`, `SRS-VER-010` |
| `502 Bad Gateway` | 외부 시스템의 유효하지 않은 응답 | `SRS-NFR-REL-003` |
| `503 Service Unavailable` | 외부 시스템 또는 local worker 일시 불가 | `SRS-NFR-REL-003` |

## 13. API 구현 완료 조건

- 각 endpoint contract test에서 표의 SRS ID를 test display name 또는 metadata로 추적한다.
- 분석과 후보 조회 test는 Git/Bitbucket write가 0회임을 검증한다.
- 선택 test는 pre/post patch policy gate와 source freshness 검증을 확인한다.
- Draft PR test는 focused test 성공만으로 publisher가 호출되지 않고, 동일 patch commit의
  `JENKINS_PR_PARITY` 전체 성공 후에만 호출됨을 확인한다.
- 실제 credential smoke test는 기본 `check`와 분리한다.
- OpenAPI 문서와 이 파일의 request/response schema가 일치해야 한다.
- 자연어 adversarial contract test는 해석만으로 외부/tool 호출이 없고, hash 확인 이후에도 기존
  구조화 gate가 동일하게 적용됨을 검증해야 한다.
