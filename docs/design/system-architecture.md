# my-agent 시스템 아키텍처

이 프로젝트는 `agent` preset과 hexagonal architecture 규칙을 사용한다.

## 기본 패키지

```text
com/example/myagent/
├── <slice>/
│   ├── adapter/
│   │   ├── in/                    # Web, scheduler, batch 진입점
│   │   └── out/                   # Persistence, 외부 API 구현
│   └── application/
│       ├── domain/
│       │   ├── model/             # Aggregate, Entity, Value Object
│       │   └── service/           # Use case 구현
│       └── port/
│           ├── in/                # Inbound use case
│           └── out/               # Outbound dependency contract
└── global/                        # 여러 slice가 공유하는 최소 기반 코드
```

`batch` preset은 batch/metrics 성격에 맞게 `application.model`과
`application.service` 패키지 규칙을 사용한다.

자연어 API는 `command` vertical slice에 둔다. 이 slice의 Embabel agent는 문장을 폐쇄된 intent DTO로
해석할 뿐 external adapter를 호출하지 않는다. 사용자의 version/hash 확인 후 application service가
기존 inbound use case에 typed command를 전달하며, 원문이나 LLM output을 직접 실행하지 않는다.
상세 경계는 [자연어 API 가드레일](natural-language-api-guardrails.md)을 따른다.

Embabel agent는 DSL로 실행 순서를 조립하거나 코드에서 agent 이름을 선택하지 않는다. annotation 기반
`@Agent`, `@Action`, `@Condition`, `@AchievesGoal`과 typed artifact를 등록하고, `AgentInvocation`이 목표
출력 타입을 만들 수 있는 agent를 자동 선택한다. 선택된 agent 내부에서는 GOAP planner가 현재 artifact와
condition을 만족하는 action 경로를 계획한다. 자연어 확인 실행도 새 경로를 만들지 않고 구조화 use case의
동일 typed artifact를 생성한다.

## 자동 검증

- DDD와 hexagonal layer 의존성
- vertical slice 간 직접 참조
- outbound port의 `Either` 반환
- JPA entity와 repository 위치
- adapter annotation과 경계 타입 위치
- native SQL 및 `JdbcTemplate` 직접 사용 제한
- Java `try/catch`, `try-with-resources` 대신 Vavr `Try` 사용 강제
- 자연어 command agent의 external tool 0개와 skill/tool 최대 5개
- 확인되지 않은 interpretation에서 기존 use case 호출 금지

```bash
./gradlew :app:architectureTest
```

## Hotfix 쓰기 경계 구현

후보 선택 이후의 쓰기 흐름은 `GuardedHotfixWorkflowAdapter`가 다음 5개 port만 순서대로 사용한다.

| 순서 | Port | 구현 책임 |
| --- | --- | --- |
| 1 | `PatchProposalPort` | Embabel이 evidence 범위 안의 완전한 파일 내용 수정안을 생성한다. |
| 2 | `PatchWorkspacePort` | 고정 source commit의 전용 worktree에서 사전·사후 파일/라인 정책을 검사하고 커밋한다. |
| 3 | `VerificationPort` | 변경 모듈 focused test와 승인 Jenkinsfile hash의 배포 제외 전체 parity를 실행한다. |
| 4 | `PatchReviewPort` | 별도 Embabel goal이 패치 범위, 증거, 테스트 적합성을 독립 검토한다. |
| 5 | `PullRequestPort` | source freshness와 동일 patch commit을 재확인하고 Bitbucket Draft PR만 생성한다. |

focused 검증은 최초 1회와 최대 2회 수정 재시도를 허용한다. parity 이후 worktree HEAD가 달라지거나
필수 stage가 하나라도 실패·미실행이면 PR을 만들지 않고 `NEEDS_HUMAN_REVIEW`를 반환한다. Git push의
Bitbucket token은 임시 `GIT_ASKPASS` 환경으로만 전달하고 파일에는 저장하지 않는다.

분석과 후보 선택 POST는 초기 상태를 JSON state에 먼저 저장하고 `202 Accepted`로 반환한다. 해당 API
호출이 제출한 background task만 외부 증거 수집 또는 hotfix workflow를 실행하며 scheduler와 polling은
없다. 같은 프로세스에서 같은 resource ID가 실행 중이면 중복 task를 추가하지 않는다. 프로세스 재시작
후 완료되지 않은 analysis 또는 `SELECTED` resource를 같은 idempotency key로 다시 호출하면 작업을 재개한다.
