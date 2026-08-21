# 에이전트 카탈로그

## 1. 현행 원칙

현재 등록된 Embabel agent는 정확히 네 개다. 이전 설계에서 사용한 `HotfixImplementationAgent`,
`ResolutionAgent`, `*Subagent` 이름은 개념적 책임 분해였으며 Spring bean이나 Embabel agent로 구현하지
않았다. hotfix orchestration과 CI refresh는 application service와 deterministic adapter가 담당한다.

agent는 typed input을 받아 추론 결과만 만든다. Jenkins, Grafana, Bitbucket, Git, filesystem과 process
작업은 LLM tool로 노출하지 않는다. `AgentInvocation`이 목표 출력 타입을 기준으로 agent를 선택하며
controller나 service가 agent 이름을 DSL로 지정하지 않는다.

## 2. 구현된 Embabel agent

| `@Agent` 이름 | 클래스 | 사람 관점의 목적 | 모델 역할 | Action/Goal |
| --- | --- | --- | --- | --- |
| `incident-analysis-agent` | `IncidentAnalysisAgent` | 제한된 Jenkins 또는 관측 증거를 source 근거가 있는 후보 목록으로 만든다. | triage → reasoning | Jenkins triage, observability triage, candidate 분석 → 후보 준비 |
| `patch-author-agent` | `PatchAuthorAgent` | 선택된 후보와 허용된 source 파일만 이용해 최소 수정안을 만든다. | reasoning | 완전한 파일별 replacement 제안 → patch proposal |
| `patch-review-agent` | `PatchReviewAgent` | 적용된 diff가 근거·정책·회귀 관점에서 안전한지 독립 검토한다. | review | 승인/수정요청/사람검토 판단 → patch review |
| `natural-language-command-agent` | `NaturalLanguageCommandAgent` | 한국어·영어 요청을 실행하지 않고 폐쇄된 typed command 초안으로 바꾼다. | triage | intent/parameter 추출 → interpretation draft |

모든 agent의 `actionRetryPolicy`는 `FIRE_ONCE`다. Embabel provider 전송과 structured binding도 설정상
각각 최대 1회다. focused 검증 실패에 따른 patch 재작성만 application workflow가 최대 2회 명시적으로
요청할 수 있다.

## 3. 결정론적 orchestration 구성요소

| 구성요소 | 목적 | 호출하는 주요 port/adapter |
| --- | --- | --- |
| `IncidentAnalysisService` / `IncidentAnalysisExecutor` | 요청 상태 저장, source/증거 수집, 후보 agent 실행 | source revision/context, Jenkins/Grafana evidence, candidate analysis, state |
| `HotfixSelectionService` | version, TTL, eligibility, freshness와 idempotency 검증 후 background workflow 접수 | state, source revision, hotfix workflow |
| `GuardedHotfixWorkflowAdapter` | patch → focused 검증 → review → parity → publish의 상위 흐름 | proposal, worktree, verification, review, pull request |
| `HotfixQueryService` | hotfix 조회와 명시적 Jenkins CI 상태 1회 갱신 | state, Jenkins read |
| `NaturalLanguageCommandService` | redaction, schema/policy/hash/TTL 확인과 typed gateway 위임 | interpreter, interpretation/execution state, confirmed dispatch |

이 구성요소들은 Embabel agent가 아니며 별도의 LLM 모델이나 prompt를 갖지 않는다.

## 4. Capability manifest

정본은 `app/src/main/resources/agent-capabilities.json`이다.

| Agent | 직접 skill | 개수 | 직접 LLM tool |
| --- | --- | ---: | ---: |
| `incident-analysis-agent` | `root-cause-analysis`, `source-context-selection`, `evidence-redaction` | 3 | 0 |
| `patch-author-agent` | `minimal-patch-proposal`, `evidence-redaction` | 2 | 0 |
| `patch-review-agent` | `independent-patch-review`, `evidence-redaction` | 2 | 0 |
| `natural-language-command-agent` | `natural-language-intent-parsing`, `evidence-redaction` | 2 | 0 |

`AgentCapabilityArchTest`는 등록 agent와 manifest의 1:1 일치, 중복 ID, agent별 skill/tool 최대 5개와
외부 tool group 부재를 검사한다. 6번째 capability가 실제로 필요해지면 목적과 typed input/output이
독립적인 새 agent로 분리해야 한다. 개수를 맞추기 위한 wrapper agent는 만들지 않는다.

## 5. 모델과 budget

| 역할 | 환경변수 | 기본 입력/출력 상한 | 사용 agent |
| --- | --- | --- | --- |
| triage | `LITELLM_TRIAGE_MODEL` | `8000 / 1500` | incident triage, 자연어 해석 |
| reasoning | `LITELLM_REASONING_MODEL` | `16000 / 4000` | candidate 분석, patch 작성 |
| review | `LITELLM_REVIEW_MODEL` | `8000 / 1500` | patch 독립 검토 |

상한은 `AGENT_AI_*_MAX_INPUT_TOKENS`, `AGENT_AI_*_MAX_OUTPUT_TOKENS`로 낮출 수 있다. prompt와 completion
본문은 observability에 기록하지 않고 token usage metric만 `/actuator/prometheus`로 노출한다.

## 6. 구현 package

```text
com.example.myagent
├── incident
│   ├── adapter/in/web
│   ├── adapter/out/{ai,http,persistence,workflow}
│   └── application/{domain,port}
├── command
│   ├── adapter/in/web
│   ├── adapter/out/{ai,module,persistence}
│   └── application/{domain,port}
├── orchestrator
└── global
```

관련 정본은 [시스템 아키텍처](../design/system-architecture.md), [실행 조건](execution-conditions.md),
[스킬](../capabilities/skills.md), [툴](../capabilities/tools.md)이다.
