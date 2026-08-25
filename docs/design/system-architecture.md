# my-agent 시스템 아키텍처

## 1. 실행 구조

애플리케이션은 Spring Boot 4.1, Java 25, Embabel 1.5 계열과 hexagonal architecture를 사용한다.
기능은 `incident`, `command`, `dashboard` vertical slice로 나뉘며 slice 간 호출은 `orchestrator`의
명시적 gateway와 inbound port를 통한다.

```text
HTTP controller
  → application service
  → outbound port
      → Embabel AI adapter       (추론만)
      → HTTP adapter             (Jenkins/Grafana/Bitbucket)
      → workflow adapter         (Git/Gradle/Docker/Draft PR)
      → JPA persistence adapter  (PostgreSQL hotfix_agent schema)
```

## 2. package 경계

```text
com/example/myagent/
├── incident/
│   ├── adapter/in/web
│   ├── adapter/out/ai
│   ├── adapter/out/http
│   ├── adapter/out/persistence
│   ├── adapter/out/workflow
│   └── application/{domain,port}
├── command/
│   ├── adapter/in/web
│   ├── adapter/out/{ai,module,persistence}
│   └── application/{domain,port}
├── dashboard/
│   ├── adapter/in/web
│   ├── adapter/out/module
│   └── application/{domain,port}
├── orchestrator/
└── global/{adapter,annotation,configuration,support}/
```

controller는 inbound use case만 호출한다. application service는 상위 흐름을 나열하고 외부 세부 처리는
port 뒤 adapter에 둔다. 다른 slice의 구현 package를 직접 참조하지 않는다.

## 3. Embabel 경계

등록 agent는 `IncidentAnalysisAgent`, `PatchAuthorAgent`, `PatchReviewAgent`,
`NaturalLanguageCommandAgent` 네 개다. 모든 agent는 annotation 기반 typed action/goal을 사용하고
`AgentInvocation`이 결과 타입에 맞는 agent를 자동 선택한다. 코드에서 agent 이름을 고르거나 Embabel
DSL로 고정 파이프라인을 조립하지 않는다.

LLM agent에는 external tool group이 없다. 모델은 후보 분석, patch proposal, review와 자연어 extraction만
수행한다. URL/query/command 생성과 실행, Git write, PR 발행은 deterministic Java adapter가 소유한다.

## 4. Incident 흐름

```text
POST analysis
  → source commit 고정
  → Jenkins 또는 Grafana bounded evidence
  → Bitbucket source context
  → IncidentAnalysisAgent
  → versioned candidate 목록

POST selection
  → version/TTL/eligibility/freshness 확인
  → 전용 worktree와 agent/hotfix/* branch
  → PatchAuthorAgent
  → 사전·사후 diff policy
  → focused Gradle 검증
  → PatchReviewAgent
  → Jenkins parity 4단계
  → source/patch commit 재확인
  → Bitbucket branch push와 Draft PR
```

Parity 4단계는 `jenkins-gradle-verification`, `jenkins-coverage-report`, `jenkins-image-build`,
`jenkins-integration-test`다. 마지막 단계는 Compose health check와 Newman 전체 스위트를 포함한다.
모든 required stage가 동일 patch commit에서 exit 0이어야 PR publisher가 실행된다.

## 5. Natural-language 흐름

자연어 해석은 `command` slice 안에서 외부 조회 없이 preview만 저장한다. `201 Created`로 반환된
interpretation의 version과 command hash를 사용자가 실행 API로 다시 보내야 기존 typed gateway가
호출된다. 원문이나 LLM output은 외부 adapter 인자가 되지 않는다.

## 6. 상태와 실행

- analysis, hotfix, interpretation과 execution은 PostgreSQL `hotfix_agent` 스키마에 저장한다.
- 반복 값은 순서 컬럼을 가진 자식 테이블로 정규화하며 secret과 원본 evidence는 저장하지 않는다.
- POST 요청만 background task를 제출한다. scheduler와 자동 polling은 없다.
- 동일 idempotency key/body는 기존 resource를 반환한다.
- 재시작 후 미완료 analysis 또는 `SELECTED` hotfix는 같은 요청으로 재개할 수 있다.
- 외부 write 전 source freshness, 기존 branch/PR과 commit을 재확인한다.

## 7. 운영 UI 경계

`dashboard` slice는 Thymeleaf와 HTMX로 SSR fragment를 제공한다. 이 slice는 `incident`나 `command`의
구현 package를 직접 참조하지 않고 `orchestrator` named gateway를 구현한 module adapter만 호출한다.
자연어 입력은 기존 해석·확인 use case로, 후보 버튼은 기존 selection use case로 전달하므로 UI가
안전 게이트를 복제하거나 우회하지 않는다.

- 실패 PR: 최초 `load`와 사용자 새로고침만 Jenkins/Bitbucket을 조회한다.
- 관측 신호: 사용자가 환경과 시간 범위를 제출할 때만 Grafana를 조회한다.
- 진행 상태: 분석과 연결된 hotfix를 `analysisId`로 결합해 하나의 네 단계 작업 카드로 표시한다.
  2초마다 agent DB 상태만 읽으며 Jenkins/Grafana polling은 수행하지 않는다.
- 다중 원인: `candidateId`마다 독립 hotfix를 유지하고 재시작 시 새 hotfix ID와 격리 branch를 사용한다.
- 작업 제어: 취소 신호를 background registry와 workflow gate에 전달한 뒤 로컬 상태를 삭제한다. 이미
  생성된 Bitbucket PR이나 Jenkins 기록은 자동 삭제하지 않는다.
- 외부 링크: Bitbucket, Jenkins와 Grafana 원본 화면을 새 탭으로 연다.

## 8. Docker 경계

`compose.yml`은 FMS 저장소를 `/workspace/fms`, runtime을 `/opt/my-agent/.agent/runtime`에 연결한다.
Newman과 nested Docker Compose는 macOS Docker daemon이 해석할 수 있도록 같은 runtime을 호스트 절대
경로에도 mount한다. `AGENT_NEWMAN_WORKSPACE_ROOT`가 그 경로를 지정하고 Testcontainers는
`TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`을 사용한다.

`./gradlew runWithLangfuse`는 백엔드와 Langfuse를 같은 Compose 프로젝트에 기동한다. PostgreSQL 인스턴스는
공유하지만 Langfuse는 `public`, agent 상태와 Liquibase 메타데이터는 `hotfix_agent` 스키마를 사용한다.
`agent-schema-init` one-shot 서비스가 스키마를 보장한 뒤 백엔드가 시작된다.

## 9. 자동 검증

`./gradlew check :app:aiMockTest`가 다음을 검사한다.

- hexagonal layer와 vertical slice 격리
- outbound `Either` 규약과 Vavr `Try` 사용
- production code의 Spring AI 직접 사용 금지와 FQCN 금지
- controller/OpenAPI/validation 규약
- agent capability manifest 일치와 skill/tool 최대 5개
- patch 경로·변경량·commit/parity/Draft PR 게이트
- Embabel action의 offline structured-output 계약

실 LLM 품질 평가는 `./gradlew aiTest`로 분리하며 Langfuse 점수는 write 권한 판정에 사용하지 않는다.
