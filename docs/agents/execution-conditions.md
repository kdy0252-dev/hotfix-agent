# 에이전트 실행 조건과 안전 게이트

## 1. 목적

이 문서는 어떤 API 요청과 상태에서 Embabel agent, 결정론적 workflow와 외부 write가 실행되는지 정의한다.
조건은 가능한 한 Java predicate와 typed artifact로 구현하고 자연어 prompt 판단에 맡기지 않는다.

## 2. 공통 진입 조건

현재 capability 제한은 `AgentCapabilityArchTest`가 검사한다. 등록된 네 agent 각각의 skill/tool이
5개를 넘으면 `check`가 실패한다. runtime startup validator는 별도로 두지 않는다. 현재 모든 agent의
external tool 수는 0개다.

| Condition | 통과 조건 | 실패 처리 | SRS |
| --- | --- | --- | --- |
| Capability budget | agent별 skill ≤ 5, tool ≤ 5 | architecture test 실패 | `SRS-NFR-MNT-006~009` |
| API trigger | 지원 endpoint에 대한 명시적 요청 | 실행 안 함 | `SRS-API-006` |
| Idempotency | key 존재, 기존 body hash와 일치 | `400` 또는 `409` | `SRS-API-001~003` |
| Request schema | 필수값과 discriminator 유효, unknown field 없음 | `400` | `SRS-API-004` |
| Source resolution | 존재하는 branch 또는 open PR | `404`/`422` | `SRS-SRC-001~005` |
| Worker capacity | 한 로컬 worker가 작업 접수 가능 | `503` | `SRS-NFR-PER-001` |

### 2.1 자연어 명령 진입 조건

```text
text length <= 2,000
  -> NaturalLanguageCommandAgent (external tool access: none)
  -> intent allowlist + typed schema validation
  -> READY_FOR_CONFIRMATION | NEEDS_CLARIFICATION | REJECTED
```

해석 결과가 `READY_FOR_CONFIRMATION`이어도 작업은 시작되지 않는다. 실행 API에서 다음 predicate를
모두 만족해야 기존 use case에 typed command를 전달한다.

```text
interpretation.status == READY_FOR_CONFIRMATION
AND interpretation.expiresAt > now
AND request.version == interpretation.version
AND constantTimeEquals(request.commandHash, recomputedCommandHash)
AND intentPolicyStillAllowed == true
```

`SELECT_CANDIDATE`는 analysis ID/version과 candidate ID를 모두 요구한다. Observability intent는
start/end/environment/source를 모두 요구하며 service/query를 typed command에 넣을 수 없다. 자연어
원문, URL, shell 또는 query 문자열은 실행 adapter의 인자가 될 수 없다. 관련 요구사항:
`SRS-NL-001~014`.

## 3. 분석 경로 조건

### 3.1 Jenkins 경로

```text
JenkinsAnalysisRequest
  AND build.completed
  AND build.result == FAILURE
  AND build.revision == source.revision
  -> BuildEvidence
```

성공 build는 `422`, revision 불일치는 `409`다. console 전체가 아니라 adapter가 크기를 제한하고
redaction한 실패 문맥만 agent 입력이 된다.

### 3.2 Observability 경로

```text
ObservabilityAnalysisRequest
  AND environment IN {DEV, QA, PROD}
  AND startAt < endAt
  AND duration <= 31 days
  AND no forbidden query/scope fields
  -> EvidencePlan(namespace, serviceName, templates, range)
```

namespace는 `fms-eu-{env}`, service는 `fms-eu-{env}-app`으로 서버가 생성한다. API 입력으로 service,
namespace 또는 query를 변경할 수 없다.

직접 매핑: `SRS-OBS-001~011`.

## 4. 동적 증거 수집 조건

| Action | 실행 조건 | 생략 조건 |
| --- | --- | --- |
| metric 조회 | 관측 분석 trigger | Jenkins 분석 |
| trace 검색 | 관측 분석 trigger와 allowlist template | trace 결과 없음 |
| trace 상세 | score 상위 trace가 존재 | trace 후보 없음 |
| Loki 조회 | 관측 분석 trigger와 allowlist template | 결과 없음 |
| source 검색 | stack frame, test, compiler 위치 또는 code symbol 존재 | source hint 전혀 없음 |
| root cause 생성 | 최소 한 개의 provenance 포함 evidence 존재 | evidence 없음 |

각 action은 SRS 한도를 넘는 응답을 typed artifact로 만들지 않아야 한다.

## 5. 후보 상태 조건

| Eligibility | 조건 | 다음 단계 |
| --- | --- | --- |
| `ELIGIBLE` | 코드 원인, source location, 반례 검토, 자동 변경 정책 모두 충족 | 사용자 선택 가능 |
| `HUMAN_ONLY` | migration/secret/infra/배포 변경 또는 운영 판단 필요 | patch 금지 |
| `INSUFFICIENT_EVIDENCE` | 근거 coverage 또는 source correlation 부족 | 추가 입력 또는 사람 검토 |

분석 완료와 후보 조회까지 Git write 횟수는 항상 0이어야 한다. 관련 요구사항:
`SRS-CAN-001~007`.

## 6. 선택 게이트

다음 조건을 모두 만족해야 `SelectedCandidate`를 생성한다.

1. `AGENT_MODE=DRAFT_PR`
2. analysis가 만료되지 않음
3. candidate가 해당 analysis에 포함됨
4. request `analysisVersion`이 최신임
5. candidate가 `ELIGIBLE`
6. source commit을 재조회한 결과가 분석 시점과 같음
7. 동일 selection의 hotfix가 없거나 기존 hotfix로 복구 가능

하나라도 실패하면 `PatchAuthorAgent`의 입력 artifact를 만들지 않는다. 관련 요구사항:
`SRS-SEL-001~006`, `SRS-SRC-006~007`, `SRS-STA-004~005`.

## 7. Patch 및 외부 write 게이트

```text
SelectedCandidate
  -> detached worktree
  -> PatchProposal
  -> pre-apply policy PASS
  -> apply
  -> actual diff policy PASS
  -> focused verification PASS
  -> independent review APPROVED
  -> JENKINS_PR_PARITY ALL STAGES SUCCESS
  -> verified patch commit == worktree HEAD
  -> source freshness PASS
  -> Draft PR write
```

정책 조건:

- 변경 파일 최대 10개
- 총 added + deleted 최대 500 lines
- migration, secret, `.env*`, certificate/key, `Jenkinsfile`, 배포 manifest/Helm/values 변경 금지
- source branch 직접 push 금지
- 검증 실패 수정 재시도 최대 2회
- 같은 실패 증거 없이 동일 patch 반복 금지
- fixed source의 `eu/Jenkinsfile` 기준 배포 제외 검증 단계 전체 실행
- 전체 Gradle verification, coverage report 확인, local Jib image build, Compose health/Newman 성공 필수
- parity 단계 skip·실패·실행 불가 시 PR 금지 및 사람 검토
- Jenkinsfile hash에 대응하는 승인 profile이 없으면 PR 금지 및 사람 검토
- parity 이후 worktree 변경 시 전체 parity 결과 무효화 및 재실행
- PR은 Draft, reviewer는 빈 목록, destination은 고정된 source destination

관련 요구사항: `SRS-GIT-001~007`, `SRS-VER-001~014`, `SRS-PR-001~005`.

## 8. Goal 달성 조건

| Goal | 조건 |
| --- | --- |
| `CandidatesPrepared` | versioned analysis에 0개 이상의 후보와 provenance 저장 완료 |
| `DraftPullRequestCreated` | 동일 patch commit의 `JENKINS_PR_PARITY` 전체 성공 후 Draft PR create/read-back 성공 |
| `NeedsHumanReview` | 정책 위반, 증거 부족, retry 소진 사유가 구조화됨 |
| `IssueResolved` | 명시적 CI refresh에서 해당 Draft PR Jenkins build `SUCCESS` 확인 |

Draft PR 생성만으로 `IssueResolved`를 달성하지 않는다.

## 9. 중단 및 재개 조건

- 상태는 action 완료 후 PostgreSQL `hotfix_agent` 스키마에 transaction으로 저장한다.
- 분석 요청 원문과 source를 관계형 컬럼에 저장한다. 재시작 시 `ANALYSIS_REQUESTED`, `ANALYZING`은
  동일 analysis ID로 분석을 다시 제출한다.
- 재시작 시 `SELECTED`, `PATCHING`, `VERIFYING`은 기존 로컬 프로세스를 이어 붙이지 않고, 고정 source
  commit과 동일 hotfix ID로 전용 worktree를 재생성하여 현재 workflow를 안전하게 재실행한다.
- 재실행 중 외부 write 전에는 기존 branch와 PR 존재 여부를 재조회한다.
- `DRAFT_PR_CREATED`는 로컬 workflow를 재실행하지 않는다. Jenkins CI 상태는 사용자의 명시적 refresh
  요청으로만 갱신한다.
- 외부 API `5xx`는 마스킹된 원인과 재시도 가능 상태로 저장한다.
- background polling과 scheduler는 구현하지 않는다.
- 사용자의 동일 idempotency 요청은 새 작업을 만들지 않고 기존 resource를 반환한다.
