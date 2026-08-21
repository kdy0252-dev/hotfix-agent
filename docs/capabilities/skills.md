# 에이전트 스킬 카탈로그

## 1. 현재 의미

이 문서의 스킬은 Embabel agent prompt가 수행하는 제한된 판단 책임이다. 외부 시스템 권한은 스킬에
포함하지 않는다. Jenkins, Grafana, Git, Gradle과 Bitbucket I/O는 application workflow의 결정론적
adapter가 수행한다.

현재 모든 agent는 `ActionRetryPolicy.FIRE_ONCE`다. LLM 호출을 같은 action에서 묵시적으로 재시도하지
않는다. `최대 2회`는 patch 적용·focused 검증 반복에만 적용하며 provider/data-binding attempt 기본값도
각 1회다.

agent 하나당 스킬과 tool은 각각 최대 5개다. 현재 agent tool은 모두 0개이며, 할당 정본은
[에이전트 카탈로그](../agents/agent-catalog.md)다.

## 2. 실제 agent manifest 스킬

| Agent | 스킬 | 목적 | 입력 예산 |
| --- | --- | --- | --- |
| `natural-language-command-agent` | natural language intent parsing, evidence redaction | 폐쇄 intent와 typed parameter 생성 | 8,000 tokens |
| `incident-analysis-agent` | root cause analysis, source context selection, evidence redaction | 제한된 증거와 코드 문맥에서 후보 생성 | 8,000 tokens |
| `patch-author-agent` | minimal patch proposal, evidence redaction | 선택된 후보만 해결하는 최소 patch 제안 | 16,000 tokens |
| `patch-review-agent` | independent patch review, evidence redaction | diff의 회귀·정책 위반·근거 불일치 검토 | 8,000 tokens |

출력 예산은 triage/review 1,500, patch reasoning 4,000 tokens다. 환경변수로 더 낮출 수 있지만
application이 정한 상한을 우회할 수는 없다.

## 3. 스킬 계약

### 3.1 Natural language intent parsing

입력은 최대 2,000자의 redaction된 단일 요청과 지원 intent schema다. 출력
`CommandInterpretationDraft`에는 intent, typed parameters, 누락·모호 필드, 명확화 질문과 rejection
reason만 포함한다.

금지 사항:

- shell, URL, PromQL, LogQL 또는 TraceQL 생성
- 정책이나 capability 변경
- 없는 ID, 시각, 환경, branch 또는 PR 번호 추측
- 자연어만으로 candidate 자동 선택
- 외부 evidence나 이전 대화에서 값 보충

최종 status, command hash, TTL과 실행 승인은 결정론적 service가 만든다.

### 3.2 Root cause analysis

입력은 redaction되고 크기가 제한된 Jenkins/Grafana 증거와 고정 source revision의 코드 문맥이다.
출력 `BugCandidate[]`은 후보별 root cause, confidence, eligibility, source locations, evidence refs,
counter evidence와 검증 계획을 포함한다. 독립 원인을 한 후보로 합치거나 evidence ref 없이 단정하지
않는다.

Jenkins 실패 구간 추출과 Grafana correlation은 별도 Embabel agent가 아니라
`IncidentAnalysisAgent`에 전달할 bounded evidence를 만드는 deterministic adapter 책임이다.

### 3.3 Source context selection

고정 commit에서 deterministic search가 찾은 파일 중 판단에 필요한 최소 코드만 사용한다. 저장소 전체,
무관한 모듈과 대용량 build log를 prompt에 넣지 않는다.

### 3.4 Minimal patch proposal

입력은 선택된 후보, 필요한 repository context와 고정 정책이다. 출력은 파일별 edit를 가진
`PatchProposal`이다. migration/changelog, secret·`.env*`·key, `Jenkinsfile`, Kubernetes·Helm·배포
manifest와 `fms-deploy`는 제안할 수 없다. LLM은 patch를 적용하거나 Git command를 실행하지 않는다.

### 3.5 Independent patch review

patch 작성 action과 분리된 모델 역할로 진단 근거, 실제 diff와 policy 결과를 비교한다. 결과는
`APPROVED`, `CHANGES_REQUIRED`, `HUMAN_REVIEW` 중 하나다. review 승인은 parity 검증을 대체하지 않는다.

### 3.6 Evidence redaction

Authorization, Cookie, token, password, connection string과 직접 식별정보를 LLM 입력·출력·로그에서
제거한다. 실제 redaction은 deterministic `SensitiveEvidenceRedactor`가 수행하며 agent skill은 redaction된 문맥만
받는다.

## 4. Agent 밖의 결정론적 책임

다음은 이전 설계에서 스킬 또는 subagent로 표현했지만 현재는 application service/adapter가 담당한다.

| 책임 | 현재 구현 |
| --- | --- |
| Jenkins log/test report 수집과 제한 | `JenkinsRestAdapter` |
| Prometheus/Tempo/Loki/alert 상관 증거 수집 | `GrafanaObservabilityAdapter` |
| patch 적용과 정책 검사 | `LocalGitPatchWorkspaceAdapter` |
| focused/parity/Newman 실행 | `LocalJenkinsParityVerificationAdapter` |
| PR 본문과 reviewer 없는 Draft 생성 | `BitbucketDraftPullRequestAdapter` |
| version/hash/TTL/idempotency 확인 | application service |

따라서 `pull-request-documentation`, `jenkins-failure-triage`, `observability-correlation`은 독립 agent
manifest 스킬이 아니다.

## 5. 평가 기준

- AI mock의 typed output schema 통과
- 후보 evidence provenance coverage
- forbidden path proposal 0건
- review fixture의 회귀 탐지
- `eu-app` 외 관측 evidence 사용 0건
- 해석만으로 외부 I/O 또는 write 0회

평가 결과는 모델과 prompt 개선 근거로만 사용하며 권한 확대 근거로 사용하지 않는다.
