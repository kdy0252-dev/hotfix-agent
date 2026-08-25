# 자연어 작업 API와 가드레일 설계

## 1. 목적

사용자가 한국어 또는 영어로 Jenkins 분석, Grafana 관측 분석, 후보 조회·선택, hotfix 상태 조회와
CI 상태 갱신을 요청할 수 있게 한다. 자연어는 편의 입력일 뿐 새로운 권한 경계가 아니다. 실행 결과와
안전 수준은 [구조화 API](../api/hotfix-agent-api.md) 호출과 동일해야 한다.

## 2. 핵심 결정

자연어 해석과 실행을 두 개의 HTTP 요청으로 분리한다.

```text
POST interpretation
  -> LLM extraction
  -> deterministic schema/policy validation
  -> immutable preview + command hash
  -> no external/tool call

POST confirmed execution
  -> version/expiry/hash/idempotency validation
  -> typed command only
  -> existing structured use case
  -> existing safety gates
```

한 번의 자연어 요청으로 바로 patch 또는 PR을 만드는 endpoint는 제공하지 않는다. “알아서 고쳐줘”는
candidate ID와 analysis version이 없으므로 `NEEDS_CLARIFICATION`이다.

## 3. 지원 명령과 권한

| Intent | 해석 단계 권한 | 확인 후 위임 | 추가 가드 |
| --- | --- | --- | --- |
| `ANALYZE_JENKINS` | 외부 I/O 없음 | Jenkins analysis use case | 실패 build/source revision 검증 |
| `ANALYZE_OBSERVABILITY` | 외부 I/O 없음 | Observability analysis use case | 명시적 범위·환경, `EU_APP` 고정 |
| `LIST_CANDIDATES` | 외부 I/O 없음 | candidate query use case | analysis ID 필수 |
| `SELECT_CANDIDATE` | 외부 I/O 없음 | selection use case | analysis ID/version, candidate ID 필수 |
| `GET_HOTFIX_STATUS` | 외부 I/O 없음 | hotfix query use case | hotfix ID 필수 |
| `REFRESH_CI_STATUS` | 외부 I/O 없음 | CI refresh use case | polling/trigger 금지 |

`NaturalLanguageCommandAgent`의 직접 capability는 skill 2개, tool 0개다. interpretation 저장과 확인 후
실행은 agent가 아니라 deterministic application service가 수행한다.

## 4. 데이터 모델

### 4.1 NaturalLanguageRequest

| Field | Type | 규칙 |
| --- | --- | --- |
| `text` | string | 1~2,000자, 한국어/영어, log에 원문 기록 금지 |
| `idempotencyKey` | header | endpoint/body hash 단위로 중복 방지 |

### 4.2 CommandInterpretation

응답은 `metadata`와 `decision`으로 중첩된다. `POST` 접수 성공 상태는 `202 Accepted`다.

| 경로 | Type | 설명 |
| --- | --- | --- |
| `metadata.interpretationId` | string | 해석 식별자 |
| `metadata.version` | positive long | 재해석 동시성 제어 |
| `metadata.request` | object | digest와 redacted preview |
| `metadata.timing` | object | 생성·만료 시각, TTL 10분 |
| `decision.status` | enum | `READY_FOR_CONFIRMATION`, `NEEDS_CLARIFICATION`, `REJECTED`, `EXPIRED`, `EXECUTED` |
| `decision.command` | nullable object | 지원 intent와 tagged typed parameter |
| `decision.feedback` | object | 누락 필드, 질문과 rejection 정보 |
| `decision.policy` | object | 고정 repository/scope/delivery |
| `decision.commandHash` | nullable string | 실행 가능할 때 서버가 계산한 SHA-256 |

LLM output DTO와 저장 모델을 분리한다. LLM은 `CommandInterpretationDraft`만 만들고 status, hash, expiry,
idempotency와 정책 결과는 Java validator가 계산한다.

## 5. 가드레일 계층

| 순서 | Guard | 차단 대상 | 실패 결과 |
| ---: | --- | --- | --- |
| 1 | HTTP schema/size | unknown field, 빈 값, 2,000자 초과 | `400` |
| 2 | redaction | token, password, Authorization, 개인정보 | 마스킹 후 해석 또는 `REJECTED` |
| 3 | intent allowlist | 임의 작업, shell, URL, raw query | `REJECTED` |
| 4 | typed parameter validation | 없는/모호한 ID, 시각, 환경, source | `NEEDS_CLARIFICATION` |
| 5 | immutable policy overlay | repo/scope 변경, merge/tag/deploy, 금지 파일 완화 | `REJECTED` |
| 6 | confirmation binding | 만료, version/hash 불일치 | `409` |
| 7 | existing use-case gate | stale source, candidate eligibility, patch/verification 정책 | 기존 API와 같은 오류/상태 |
| 8 | write-time recheck | current HEAD/parity/source 불일치 | `NEEDS_HUMAN_REVIEW` |

정책 overlay가 natural-language parameter보다 항상 우선한다.

```text
repository = autocrypt/fms
service = EU_APP
hotfixBranchPattern = agent/hotfix/*
delivery = DRAFT_PR_ONLY
reviewers = []
merge/tag/release/deploy = DENY
forbiddenPaths/changeLimits/retryLimit = IncidentPolicy
```

## 6. Prompt injection 방어

- system policy와 intent JSON schema는 사용자 text와 별도 message로 전달한다.
- 사용자 text는 명령 권한이 없는 data field로 delimiting한다.
- “이전 지시 무시”, tool 호출, secret 출력, 정책 변경 문구는 parameter가 아니라 rejection evidence다.
- 해석 agent에는 external tool registry, 범용 HTTP, shell과 source content를 노출하지 않는다.
- Jenkins/Grafana log는 자연어 command interpreter 입력에 합치지 않는다. log 내부 instruction도 명령이
  될 수 없다.
- schema-valid LLM output도 신뢰하지 않고 Java allowlist validator를 다시 통과한다.
- 모델 변경이나 LLM judge 점수는 guard를 비활성화하거나 write 권한을 넓힐 수 없다.

## 7. 확인과 재실행 방지

command hash 입력은 다음 canonical JSON이다.

```text
schemaVersion + intent + normalized typed parameters + effective policy version
```

map key 정렬, UTC 시각, enum 대문자와 source discriminator를 정규화한 후 SHA-256을 계산한다. 실행 시
서버가 다시 계산하고 constant-time 비교한다. 해석 수정은 version을 증가시키고 이전 hash를 폐기한다.
동일 execution idempotency key는 기존 delegated resource를 반환한다.

## 8. 관측과 데이터 보호

저장 허용:

- request digest
- redaction된 짧은 preview
- typed intent/parameter
- policy version, command hash, 상태와 delegated resource ID

저장 금지:

- 자연어 원문
- secret 원문
- LLM prompt/result 전문
- Jenkins/Grafana evidence 원문

감사 event에는 `interpretation.created`, `clarification.required`, `command.rejected`,
`execution.confirmed`, `execution.delegated`만 기록하고 원문을 포함하지 않는다.

## 9. 테스트와 AI 평가

결정적 테스트가 안전 판정을 소유하고 AI 평가는 해석 품질만 측정한다.

| Test | Fixture | 기대 결과 |
| --- | --- | --- |
| intent extraction | 한국어/영어 동등 요청 | 같은 typed command |
| ambiguity | “어제 prod 오류 고쳐줘” | 누락 range/source/candidate 질문, tool 0회 |
| prompt injection | 정책 무시·shell/tool 호출 문구 | `REJECTED`, capability 변화 없음 |
| scope override | US app, raw LogQL 지정 | `REJECTED`, Grafana 호출 0회 |
| write bypass | 바로 merge/deploy 요청 | `REJECTED`, Git/Bitbucket write 0회 |
| confirmation | hash/version 변조·만료 | `409`, delegated use case 0회 |
| parity | 동일 명령을 구조화 API와 자연어 API로 실행 | 동일 use-case input과 gate 결과 |
| Embabel mock | typed draft와 prompt capture | 외부 LLM 없이 schema/guard 검증 |
| LLM judge | 해석 정확성·명확화 질문 품질 | Langfuse score 기록, 권한 판정에는 미사용 |

관련 요구사항: `SRS-NL-001~014`, `SRS-NFR-SEC-001~006`, `SRS-NFR-MNT-006~009`.
