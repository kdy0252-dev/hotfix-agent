# 에이전트 스킬 카탈로그

## 1. 스킬의 의미

이 문서의 스킬은 운영 장애 에이전트가 재사용하는 bounded prompt, 입력/출력 schema, 평가 fixture의
묶음이다. 외부 시스템 접근 권한은 포함하지 않는다. 외부 I/O는 [툴 카탈로그](tools.md)의 deterministic
adapter만 수행한다.

각 스킬은 다음을 가져야 한다.

- 명확한 typed input과 output
- 허용된 evidence field와 token budget
- schema validation
- prompt/result의 secret redaction
- 최소 하나의 정상 fixture와 반례 fixture
- 실패 시 재요청 1회, 이후 `NeedsHumanReview`

한 agent/subagent에 직접 할당할 수 있는 스킬은 최대 5개다. 6번째 스킬이 필요하면 관련 목적을 typed
artifact 경계의 하위 에이전트로 분리한다. 부모는 하위 에이전트 스킬을 상속하지 않는다. 현재 할당과
개수는 [에이전트 카탈로그](../agents/agent-catalog.md#6-agent별-허용-스킬과-툴)를 정본으로 사용한다.

## 2. MVP 스킬 목록

| Skill ID | 이름 | 목적 | 모델 역할 | SRS |
| --- | --- | --- | --- | --- |
| `SKILL-TRIAGE-JENKINS` | `jenkins-failure-triage` | 실패 stage, exception chain, test와 source hint 구조화 | triage | `SRS-JEN-004~005` |
| `SKILL-TRIAGE-OBS` | `observability-correlation` | metric, trace, log의 시간·trace ID·service 상관관계 분석 | triage | `SRS-OBS-003`, `SRS-OBS-007~010` |
| `SKILL-ROOT-CAUSE` | `root-cause-analysis` | 증거별 독립 원인 후보와 반례 생성 | reasoning | `SRS-CAN-002~005`, `SRS-CAN-007` |
| `SKILL-SOURCE-CONTEXT` | `source-context-selection` | source 검색 결과에서 필요한 최소 코드 문맥 선택 | reasoning | `SRS-CAN-001`, `SRS-NFR-PER-003` |
| `SKILL-PATCH` | `minimal-patch-proposal` | 선택된 후보에 한정된 최소 patch 제안 | reasoning | `SRS-GIT-003~006`, `SRS-VER-006` |
| `SKILL-REVIEW` | `independent-patch-review` | diff와 검증 결과의 결함·회귀·근거 불일치 검토 | review | `SRS-VER-007` |
| `SKILL-PR-DOC` | `pull-request-documentation` | Draft PR 설명과 release note 초안 작성 | review | `SRS-PR-004` |
| `SKILL-REDACTION` | `evidence-redaction` | LLM 전달 전 secret/식별정보 제거 결과 검증 | deterministic | `SRS-NFR-SEC-002~005` |
| `SKILL-NL-INTENT` | `natural-language-intent-parsing` | 한국어/영어 문장을 폐쇄된 intent, typed parameter와 명확화 질문으로 변환 | triage | `SRS-NL-001~006`, `SRS-NL-010~013` |

## 3. 스킬 계약

### 3.1 jenkins-failure-triage

입력:

- build metadata
- parser가 제한한 console excerpt
- 실패 test report summary
- source revision

출력 `JenkinsTriageSummary`:

- failure category
- failed stage/module/test
- exception chain
- source hints
- missing evidence

금지: console 원문 재요청, Jenkins tool 호출, shell command 생성.

### 3.2 observability-correlation

입력:

- 고정된 environment/namespace/service/time range
- 제한된 metric series
- 제한된 trace summaries/details
- 제한된 log excerpts

출력 `ObservabilityTriageSummary`:

- anomaly type와 영향
- 시간 순서
- correlation keys
- supporting/counter evidence
- 추가 evidence 종류 제안

금지: PromQL/LogQL/TraceQL 생성, 다른 service 또는 시간 범위 요청.

### 3.3 root-cause-analysis

입력은 redaction된 triage summary, provenance, source context다. 출력은 서로 합치지 않은
`BugCandidate[]`이며 각 후보는 root cause, confidence, source location, evidence refs, counter evidence,
eligibility와 검증 계획을 포함한다.

금지: evidence ref 없는 단정, 여러 독립 원인의 한 후보 병합, patch 적용.

### 3.4 source-context-selection

deterministic source search 결과에서 원인 판단에 필요한 파일과 line 주변 문맥만 선택한다. 파일 내용
전체를 불필요하게 LLM에 전달하지 않는다. 검색 대상은 고정된 source revision이다.

### 3.5 minimal-patch-proposal

입력은 `SelectedCandidate`, repository context와 `IncidentPolicy`다. 출력은 파일별 edit와 설명을 가진
`PatchProposal`이다. proposal 생성 시에도 다음 경로는 포함할 수 없다.

- migration/changelog
- secret, `.env*`, certificate/key
- `Jenkinsfile`
- Kubernetes, Helm, deployment manifest와 values
- `fms-deploy`

스킬은 patch를 직접 적용하거나 Git command를 실행하지 않는다.

focused 검증은 수정 피드백에만 사용한다. 이 스킬의 patch는 deterministic verifier가 고정 Jenkinsfile
기준 `JENKINS_PR_PARITY` 전체 단계를 성공시키기 전에는 Draft PR 입력이 될 수 없다.

### 3.6 independent-patch-review

patch 작성 prompt와 분리된 context/model 역할을 사용한다. 진단 근거, 실제 diff, policy 결과와 검증
결과를 비교해 `APPROVED`, `CHANGES_REQUIRED`, `HUMAN_REVIEW` 중 하나를 반환한다.

### 3.7 pull-request-documentation

다음 항목을 포함한 구조화된 `PullRequestDocument`를 만든다.

- analysis와 source revision
- 선택 candidate와 evidence 요약
- 변경 파일/라인 policy 결과
- focused 검증 결과
- Jenkins parity profile, Jenkinsfile hash, 검증 patch commit과 전체 stage 결과
- 운영 담당 `BE팀`
- Draft이며 사람 승인이 필요하다는 문구
- reviewer 빈 목록 정책

### 3.8 evidence-redaction

redaction 자체는 deterministic service로 수행한다. 스킬 계약은 redaction 결과에 Authorization, Cookie,
token, password, connection string 또는 직접 식별정보가 남지 않았는지 검사한다. 원문 secret은 output과
관측 로그에 포함하지 않는다.

### 3.9 natural-language-intent-parsing

입력은 최대 2,000자의 redaction된 단일 요청과 지원 intent schema다. 출력 `CommandInterpretationDraft`는
intent, typed parameters, missing/ambiguous fields, clarification questions와 rejection reason만 포함한다.

금지:

- shell, URL, PromQL, LogQL 또는 TraceQL 생성
- 기존 정책, tool 권한이나 agent capability 변경
- 없는 ID, 시각, 환경, branch 또는 PR 번호 추측
- 자연어만으로 candidate 자동 선택
- 외부 evidence나 이전 대화에서 parameter 보충

결정론적 validator가 최종 status와 command hash를 만든다. LLM이 만든 hash, 실행 승인 또는 정책 판정은
신뢰하지 않는다.

## 4. 스킬 선택 조건

| 조건 | 선택되는 스킬 |
| --- | --- |
| Jenkins trigger | `jenkins-failure-triage` |
| Observability trigger | `observability-correlation` |
| 최소 evidence와 source context 확보 | `root-cause-analysis` |
| source 검색 결과가 token budget 초과 | `source-context-selection` |
| 유효한 사용자 선택과 `DRAFT_PR` mode | `minimal-patch-proposal` |
| patch 적용 및 검증 완료 | `independent-patch-review` |
| review 및 Jenkins parity 검증 승인 | `pull-request-documentation` |
| 모든 LLM 호출 전 | `evidence-redaction` |
| 자연어 interpretation 요청 | `natural-language-intent-parsing`, 이후 `evidence-redaction` 검증 |

## 5. 평가 기준

- Jenkins fixture에서 실패 stage/test/source hint recall
- 관측 fixture에서 `eu-app` 외 evidence 사용률 0%
- 후보의 provenance coverage 100%
- forbidden path proposal 비율 0%
- 동일 evidence에서 patch 반복 생성 방지
- review fixture에서 의도된 회귀 탐지
- 모든 typed output의 schema pass rate

평가 결과는 모델 선택 근거로만 사용하며 write 권한 확대 근거로 사용하지 않는다.
