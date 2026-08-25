# Hotfix Agent API 계약

## 1. 범위

이 문서는 현재 구현된 HTTP API와 [SRS](../requirements/SRS.md)의 매핑을 설명한다. 모든 endpoint의
base path는 `/api/v1`이며 JSON unknown field는 `400 Bad Request`로 거부한다. 서비스는 기본적으로
`127.0.0.1`에 bind한다.

- 분석, 선택, 자연어 해석과 실행은 요청이 있을 때만 수행한다. background polling은 없다.
- 분석·선택·자연어 해석·자연어 실행 `POST`는 `Idempotency-Key`가 필수다.
- 분석과 선택은 `202 Accepted`, 자연어 해석 생성은 `201 Created`, 자연어 실행은 `202 Accepted`다.
- 식별자는 현재 UUID 문자열이다. 클라이언트는 형식이나 생성 순서에 의존하면 안 된다.
- 분석과 hotfix 조회는 도메인 record를 그대로 JSON으로 직렬화한다.

비동기 접수 응답 `AcceptedResource`:

```json
{
  "resourceId": "4be94799-250f-460a-9208-aa8b0909c828",
  "status": "ANALYSIS_REQUESTED",
  "statusUrl": "/api/v1/analyses/4be94799-250f-460a-9208-aa8b0909c828"
}
```

관련 요구사항: `SRS-API-001~006`, `SRS-NFR-SEC-001~005`.

## 2. Source 모델

```json
{"type":"BRANCH","branchName":"main"}
```

```json
{"type":"PULL_REQUEST","pullRequestId":1292}
```

branch 입력은 원격 head를 base commit과 Draft PR destination으로 사용한다. PR 입력은 open PR의 source
commit과 source branch를 사용한다. 원래 source branch에 직접 push하지 않는다.

관련 요구사항: `SRS-SRC-001~007`.

## 3. Endpoint와 SRS 매핑

| Method | Path | 성공 | 목적 | SRS |
| --- | --- | --- | --- | --- |
| `POST` | `/analyses/jenkins` | `202` | 선택한 실패 Jenkins build 분석 | `SRS-JEN-001~005`, `SRS-SRC-001~007` |
| `POST` | `/analyses/observability` | `202` | 지정 환경·시간 범위의 EU app 관측 분석 | `SRS-OBS-001~011`, `SRS-SRC-001~007` |
| `GET` | `/analyses/{analysisId}` | `200` | 분석 상태 조회 | `SRS-CAN-002~007`, `SRS-STA-001~005` |
| `GET` | `/analyses/{analysisId}/candidates` | `200` | 후보 목록 조회 | `SRS-CAN-002~007` |
| `POST` | `/analyses/{analysisId}/selections` | `202` | 선택한 후보의 hotfix 접수 | `SRS-SEL-001~006`, `SRS-GIT-001~007`, `SRS-VER-001~014` |
| `GET` | `/hotfixes/{hotfixId}` | `200` | patch·검증·Draft PR 상태 조회 | `SRS-VER-001~014`, `SRS-PR-001~005` |
| `POST` | `/hotfixes/{hotfixId}/ci-status-refresh` | `200` | Jenkins PR build 상태 1회 조회 | `SRS-PR-006~007` |
| `POST` | `/natural-language/interpretations` | `201` | 자연어를 typed command로 해석 | `SRS-NL-001~006`, `SRS-NL-013` |
| `GET` | `/natural-language/interpretations/{id}` | `200` | 해석 조회 | `SRS-NL-003~006`, `SRS-NL-008` |
| `POST` | `/natural-language/interpretations/{id}/executions` | `202` | 확인된 command를 기존 use case로 실행 | `SRS-NL-007~014` |

## 4. Jenkins 분석

```http
POST /api/v1/analyses/jenkins
Idempotency-Key: jenkins-pr-1292-1
Content-Type: application/json
```

```json
{
  "jobPath": "FMS-EU/job/PR-1292",
  "buildNumber": 1,
  "source": {"type":"PULL_REQUEST","pullRequestId":1292}
}
```

`jobPath`는 `FMS-EU` root 아래만 허용한다. build는 양수이며 완료된 실패 build여야 한다. build revision과
고정 source commit이 다르면 `409`, 성공 build는 `422`다. 이 API는 Jenkins build를 시작하거나
중단하지 않는다.

관련 요구사항: `SRS-JEN-001~005`, `SRS-NFR-PER-001`.

## 5. Grafana 관측 분석

```http
POST /api/v1/analyses/observability
Idempotency-Key: obs-prod-20260821T125000-131000
Content-Type: application/json
```

```json
{
  "startAt": "2026-08-21T12:50:00+09:00",
  "endAt": "2026-08-21T13:10:00+09:00",
  "environment": "PROD",
  "source": {"type":"BRANCH","branchName":"main"}
}
```

| 필드 | 검증 |
| --- | --- |
| `startAt`, `endAt` | timezone offset 필수, start < end, 최대 31일 |
| `environment` | `DEV`, `QA`, `PROD` |
| `source` | 2절 Source 모델 |

입력은 시간 범위다. 시스템이 `namespace=fms-eu-{env}`와
`service_name=fms-eu-{env}-app`을 고정한다. `service`, `namespace`, raw PromQL/LogQL/TraceQL은
받지 않으며 server allowlist query만 사용한다.

관련 요구사항: `SRS-OBS-001~011`, `SRS-NFR-PER-002~003`.

## 6. 분석과 후보 조회

```http
GET /api/v1/analyses/{analysisId}
GET /api/v1/analyses/{analysisId}/candidates
```

분석 조회는 `AnalysisSession(identity, snapshot, result)` 구조다.

```json
{
  "identity": {
    "analysisId": "4be94799-250f-460a-9208-aa8b0909c828",
    "version": 1,
    "requestHash": "sha256-value"
  },
  "snapshot": {
    "source": {"type":"PULL_REQUEST","branchName":null,"pullRequestId":1292},
    "sourceRevision": {
      "commit": "0123456789abcdef0123456789abcdef01234567",
      "destinationBranch": "feature/compile-failure-test",
      "provenance": "bitbucket:pullrequest:1292"
    },
    "createdAt": "2026-08-21T04:00:00Z",
    "expiresAt": "2026-08-22T04:00:00Z"
  },
  "result": {"status":"CANDIDATES_READY","candidates":[],"failureReason":null}
}
```

후보 endpoint는 `BugCandidate` 배열을 반환한다. 각 항목은 `identity`, `evidence`, `recommendation`으로
중첩된다. 조회는 read-only이며 worktree, branch와 PR을 만들지 않는다.

## 7. 후보 선택과 hotfix 접수

```http
POST /api/v1/analyses/{analysisId}/selections
Idempotency-Key: select-analysis-candidate
Content-Type: application/json
```

```json
{"candidateId":"candidate-uuid","analysisVersion":1}
```

시스템은 candidate 소속, 최신 version/source, `ELIGIBLE`, evidence coverage, idempotency와
`AGENT_MODE=DRAFT_PR`을 다시 확인한다. 성공 응답 필드명은 모든 비동기 접수와 동일하게
`resourceId`다.

```json
{
  "resourceId": "cc09993c-bcac-407d-8cb9-6c52011a169e",
  "status": "SELECTED",
  "statusUrl": "/api/v1/hotfixes/cc09993c-bcac-407d-8cb9-6c52011a169e"
}
```

candidate 없음은 `404`, stale version/source는 `409`, 선택 불가 또는 `REPORT_ONLY`는 `422`다.

## 8. Hotfix 조회

```http
GET /api/v1/hotfixes/{hotfixId}
```

응답은 `HotfixResource(identity, progress, publication)` 구조다.

```json
{
  "identity": {
    "hotfixId": "cc09993c-bcac-407d-8cb9-6c52011a169e",
    "analysisId": "4be94799-250f-460a-9208-aa8b0909c828",
    "candidateId": "candidate-uuid"
  },
  "progress": {
    "status": "DRAFT_PR_CREATED",
    "branchName": "agent/hotfix/cc09993c-null-check",
    "changedFiles": 2,
    "changedLines": 34,
    "verification": {
      "focusedAttempts": 1,
      "provenance": {
        "baseCommit": "base-sha",
        "patchCommit": "patch-sha",
        "jenkinsfile": {"path":"eu/Jenkinsfile","sha256":"sha256-value","profileVersion":1}
      },
      "stages": [
        {"name":"jenkins-gradle-verification","exitCode":0,"required":true},
        {"name":"jenkins-coverage-report","exitCode":0,"required":true},
        {"name":"jenkins-image-build","exitCode":0,"required":true},
        {"name":"jenkins-integration-test","exitCode":0,"required":true}
      ]
    },
    "humanReviewReason": null
  },
  "publication": {
    "draftPullRequestUrl": "https://bitbucket.org/autocrypt/fms/pull-requests/1295",
    "ciBuildUrl": "https://jenkins.autocrypt-fms.io/job/FMS-EU/job/PR-1295/1/",
    "ciResult": "PENDING"
  }
}
```

현재 background workflow는 안전 게이트를 순차 실행한 뒤 terminal 결과를 저장한다. 클라이언트는
중간 상태가 모든 내부 단계마다 즉시 갱신된다고 가정하면 안 된다. required parity stage 누락·실패,
commit 불일치, 정책 위반은 Draft PR 없이 `NEEDS_HUMAN_REVIEW`로 끝난다.

## 9. CI 상태 수동 갱신

```http
POST /api/v1/hotfixes/{hotfixId}/ci-status-refresh
```

이 API만 해당 Draft PR의 Jenkins 상태를 한 번 읽는다. `SUCCESS`이면 `RESOLVED`, 그 외에는 현재 상태와
build URL을 반환한다. build trigger와 background polling은 없다.

## 10. 자연어 해석과 실행

자연어 기능은 범용 명령 실행기가 아니다. LLM은 폐쇄된 intent와 typed parameter만 만들고, 사용자가
version과 command hash를 확인한 후 기존 구조화 use case가 실행된다.

### 10.1 해석 생성

```http
POST /api/v1/natural-language/interpretations
Idempotency-Key: nl-jenkins-pr-1292
Content-Type: application/json
```

```json
{"text":"FMS-EU PR-1292의 1번 실패 빌드를 분석해줘"}
```

성공은 `201 Created`이고 `Location` header를 포함한다. 응답은
`CommandInterpretation(metadata, decision)` 구조다.

```json
{
  "metadata": {
    "interpretationId": "interpretation-uuid",
    "version": 1,
    "request": {"digest":"sha256-value","redactedPreview":"FMS-EU PR-1292..."},
    "timing": {"createdAt":"2026-08-21T04:00:00Z","expiresAt":"2026-08-21T04:10:00Z"}
  },
  "decision": {
    "status": "READY_FOR_CONFIRMATION",
    "command": {
      "intent": "ANALYZE_JENKINS",
      "parameters": {
        "jobPath":"FMS-EU/job/PR-1292",
        "buildNumber":1,
        "source":{"type":"PULL_REQUEST","branchName":null,"pullRequestId":1292}
      }
    },
    "feedback": {
      "missingFields":[],
      "clarificationQuestions":[],
      "rejectionCode":null,
      "rejectionMessage":null
    },
    "policy": {
      "repository":"autocrypt/fms",
      "service":"EU_APP",
      "delivery":"DRAFT_PR_ONLY",
      "policyVersion":"v1"
    },
    "commandHash":"sha256-value"
  }
}
```

지원 intent는 `ANALYZE_JENKINS`, `ANALYZE_OBSERVABILITY`, `LIST_CANDIDATES`,
`SELECT_CANDIDATE`, `GET_HOTFIX_STATUS`, `REFRESH_CI_STATUS` 여섯 개다. 필수값이 없으면
`NEEDS_CLARIFICATION`, 정책 변경·raw query·merge/tag/deploy 요청은 `REJECTED`다. 해석 단계는 외부
시스템을 호출하지 않는다.

### 10.2 확인 후 실행

```http
POST /api/v1/natural-language/interpretations/{interpretationId}/executions
Idempotency-Key: execute-interpretation-v1
Content-Type: application/json
```

```json
{"interpretationVersion":1,"commandHash":"sha256-value"}
```

서버는 10분 만료, version/hash, idempotency와 현재 정책을 검증하고 typed command만 기존 use case에
위임한다. 응답은 `CommandExecution(identity, result, executedAt)`이며 `result`에 위임된
`resourceId`, `status`, `statusUrl`, `itemIds`가 들어간다.

상세 위협 모델은 [자연어 API 가드레일](../design/natural-language-api-guardrails.md)을 따른다.

## 11. 상태 코드

| Status | 조건 |
| --- | --- |
| `200` | 조회, CI 상태 1회 갱신 |
| `201` | 자연어 interpretation 생성 |
| `202` | 분석·selection·자연어 실행 접수 |
| `400` | validation, unknown field, idempotency key 누락 |
| `404` | analysis, candidate, hotfix, branch 또는 PR 없음 |
| `409` | idempotency 충돌, stale source/version/hash, 잘못된 상태 전이 |
| `422` | 지원 불가 build/후보/정책/자연어 또는 parity 실행 불가 |
| `502` | 외부 시스템의 유효하지 않은 응답 |
| `503` | 외부 시스템 또는 local worker 일시 불가 |

## 12. 현재 검증 상태

- controller/service contract, idempotency, safety gate와 adapter test가 `./gradlew check`에 포함된다.
- 네 Embabel agent의 mock 평가가 `./gradlew :app:aiMockTest`에 포함된다.
- 실제 credential smoke와 실 PR 생성은 기본 test에서 분리된다.
- 2026-08-21에 FMS PR #1292를 입력으로 전체 parity와 Newman 20/20 성공 후 reviewer 없는 Draft PR
  #1295를 생성했다.
- PR #1295의 Jenkins build 시작까지 확인했으며 최종 성공 여부는 이 문서에서 확정하지 않는다.
