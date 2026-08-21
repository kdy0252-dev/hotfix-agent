# my-agent

`autocrypt/fms`의 Jenkins 빌드 실패와 Grafana 관측 증거를 분석하고, 사용자가 선택한 버그 후보만
제한된 범위에서 수정하여 Bitbucket Draft PR로 발행하는 로컬 Spring Boot 애플리케이션이다.
LLM orchestration은 Embabel을 사용하고 모델 호출은 사내 LiteLLM OpenAI-compatible API를 통한다.

## 현재 구현 상태

- Jenkins `FMS-EU` 실패 build 분석 API
- Grafana Loki, Tempo, Prometheus와 alert를 이용한 `DEV`, `QA`, `PROD`의 `eu-app` 분석 API
- branch 또는 open PR source commit 고정과 Bitbucket source context 조회
- 선택 가능한 `BugCandidate` 생성과 명시적 후보 선택
- 격리된 `agent/hotfix/*` worktree에서 최대 10개 파일·500줄 수정
- focused Gradle 검증, 독립 AI review, Jenkins 동등 로컬 parity 검증
- Gradle/coverage/Jib/Compose health/Newman 전체 통과 후 reviewer 없는 Draft PR 생성
- Draft PR Jenkins 상태의 명시적 1회 갱신 API
- 자연어 해석과 version/hash 확인 실행이 분리된 API
- JSON 상태 저장, idempotency, LLM 입력·출력 budget과 재시도 제한
- Embabel AI mock 및 LiteLLM judge/Langfuse 평가

2026-08-21에 FMS PR #1292의 의도적 컴파일 실패를 대상으로 전체 흐름을 검증했다. 로컬 parity 4단계와
Newman 본 스위트 20건이 모두 성공한 뒤 Bitbucket Draft PR #1295가 생성됐다.

## 안전 경계

- 자동 polling, merge, approve, tag, release, deploy와 rollback을 제공하지 않는다.
- migration, secret, `Jenkinsfile`, Kubernetes/Helm/배포 manifest는 수정하지 않는다.
- 원본 branch나 원본 PR source branch에 직접 push하지 않는다.
- Grafana와 Jenkins는 읽기 전용이며 Jenkins build를 trigger하거나 중단하지 않는다.
- 분석만으로는 Git write를 수행하지 않는다. 사용자가 후보 ID와 분석 version을 선택해야 hotfix가 시작된다.
- 필수 parity stage가 하나라도 실패·누락되면 Draft PR을 생성하지 않는다.
- 모든 Embabel action은 `FIRE_ONCE`이며 provider와 data binding 자동 호출 횟수는 각각 1회다.

## 구조

```text
my-agent/
├── app/src/main/java/com/example/myagent/
│   ├── incident/                 # 분석, 후보, hotfix와 외부 adapter
│   ├── command/                  # 자연어 해석·확인·typed dispatch
│   ├── orchestrator/             # vertical slice 간 명시적 gateway
│   └── global/                   # 공통 설정, annotation, redaction/budget
├── app/src/{test,aiMockTest,aiEvaluationTest}/
├── build-logic/                  # convention plugin, Checkstyle, architecture 규칙
├── docs/                         # 요구사항, 설계, API, agent/tool, 테스트 문서
├── infra/langfuse/               # 로컬 AI 평가 스택
├── scripts/                      # 환경 설정과 실행 스크립트
├── Dockerfile
└── compose.yml
```

구현은 hexagonal architecture와 vertical slice 경계를 따른다. LLM agent에는 외부 tool group을 노출하지
않고, Jenkins/Grafana/Bitbucket/Git/process 작업은 application workflow가 typed port로 호출한다.

## 사전 조건

- Java 25
- Docker Desktop
- `~/workspace/fms` 또는 `AGENT_FMS_REPOSITORY_PATH`가 가리키는 FMS Git 저장소
- Bitbucket access token, Jenkins API token, Grafana service account token, LiteLLM API key

Jenkins와 Grafana의 사설 인증서는 시연 환경에 한해 `*_TLS_VERIFY=false`를 사용한다.

## 환경 설정

```zsh
./scripts/setup-env-local.zsh
```

스크립트는 자격증명을 숨김 입력으로 받아 Git 비추적 파일 `.env.local`을 권한 `600`으로 생성한다.
Grafana datasource UID는 API로 자동 탐색하고 LiteLLM 모델은 프로젝트 model registry와 대조한다.
변수 목록과 기본값은 [.env.local.example](.env.local.example)을 참고한다.

Embabel 실행에서는 `LITELLM_API_KEY`를 사용한다. Docker entrypoint와 로컬 실행 스크립트는
OpenAI-compatible SDK가 다른 키를 우선하지 않도록 프로세스의 `OPENAI_API_KEY`도 LiteLLM 키로
설정한다. 별도로 보관한 실제 OpenAI key는 현재 agent 모델 호출에 사용하지 않는다.

## 실행

호스트에서 실행:

```zsh
./scripts/run-local.zsh
```

실제 Draft PR 흐름을 포함한 Docker 실행:

```zsh
docker compose --env-file .env.local up --build --detach
curl --fail http://127.0.0.1:8080/actuator/health
```

Compose는 FMS 저장소, `.agent/runtime`, Gradle cache와 Docker socket을 연결한다. Docker Desktop에서
Testcontainers는 `host.docker.internal`을 사용하며 Newman fixture와 Compose volume은
`AGENT_NEWMAN_WORKSPACE_ROOT`의 호스트 절대 경로를 사용한다.

OpenAPI UI는 `http://127.0.0.1:8080/swagger-ui.html`, JSON은 `/v3/api-docs`에서 확인한다.

## 작업 흐름

```text
명시적 분석 API
  → Jenkins 또는 Grafana 증거 수집
  → Embabel 후보 분석
  → 후보 목록 조회
  → 사용자의 candidate ID + analysis version 선택
  → patch 제안과 격리 worktree 적용
  → focused 검증과 독립 review
  → Gradle → coverage → Jib → Compose health/Newman
  → source/patch commit 재확인
  → Bitbucket Draft PR
  → 선택적 CI 상태 1회 갱신
```

구조화 API와 예시는 [Hotfix Agent API](docs/api/hotfix-agent-api.md)에 있다.

## 검증

```zsh
./gradlew check :app:aiMockTest
```

위 명령은 단위·contract·architecture·Checkstyle 테스트와 외부 LLM을 사용하지 않는 Embabel mock을
실행한다. `src/main/java`의 Java `try/catch` 사용, production code의 Spring AI 직접 의존, FQCN 사용,
agent capability 5개 초과 등은 architecture test가 차단한다.

외부 LiteLLM judge와 로컬 Langfuse까지 포함하려면 다음을 실행한다.

```zsh
./gradlew aiTest
```

상세 테스트 계층은 [AI 테스트 실행](docs/testing/ai-testing.md)을 참고한다.

## 문서

[문서 카탈로그](docs/README.md)에서 CRS, SRS, 시스템 설계, API 계약, agent/skill/tool과 운영 가이드를
확인할 수 있다. `.agent/plans`와 `.agent/guides`는 초기 의사결정과 개인 설정 절차의 기록이며 현재
제품 동작의 정본은 `README.md`와 `docs/`다.
