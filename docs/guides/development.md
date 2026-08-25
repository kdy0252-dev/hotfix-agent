# my-agent 개발 가이드

## 필수 환경

- Java 25
- 생성된 Gradle wrapper 사용

## 실행

```bash
./scripts/setup-env-local.zsh
./scripts/run-local.zsh
```

실제 hotfix branch와 Draft PR을 생성하려면 setup 시 `AGENT_MODE=DRAFT_PR`을 선택한다.
기본값 `REPORT_ONLY`에서는 후보 선택 이후 쓰기 흐름을 실행하지 않는다. 로컬 데모에서는 Jenkins와
Grafana 모두 `tls-verify=false`이며 merge, tag, release, deploy는 구현되어 있지 않다.

## Docker로 실제 서버 실행

`.env.local`의 `AGENT_FMS_REPOSITORY_PATH`는 호스트의 FMS 저장소 경로다. 값이 없으면 프로젝트의
형제 디렉터리인 `../fms`를 사용한다. Compose는 이 저장소와 Docker socket을 컨테이너에 연결해
전용 worktree 검증과 통합 테스트를 실행한다.

```bash
docker compose --env-file .env.local up --build --detach
curl --fail http://127.0.0.1:8080/v3/api-docs >/dev/null
```

백엔드와 Langfuse를 같은 Docker Compose 프로젝트로 실행하고 모든 AI generation을 평가하려면 다음
Gradle task를 사용한다.

```bash
./gradlew runWithLangfuse
```

Docker Desktop에는 `my-agent`, `langfuse-web`, `langfuse-worker`, PostgreSQL, ClickHouse, Redis와 MinIO가
`my-agent-ai-test` 프로젝트 아래 표시된다. 백엔드는 `http://127.0.0.1:8080`, Langfuse는
`http://127.0.0.1:13000`에서 확인한다.

Compose 실행 모드는 `DRAFT_PR`로 고정한다. 원본 branch에 직접 push하지 않으며 parity 검증을 모두
통과한 `agent/hotfix/*` branch와 Draft PR만 생성할 수 있다. 애플리케이션 프로세스에서는
OpenAI-compatible SDK가 다른 OpenAI 키를 우선하지 않도록 `OPENAI_API_KEY`도 LiteLLM 키로 설정한다.
Docker Desktop 내부에서 실행되는 Testcontainers는 `host.docker.internal`을 사용해 sibling container의
공개 포트에 접근한다. 이 설정이 없으면 integration test가 `Connection refused`로 실패한다.
Jenkins와 동일한 검증 task를 실행하되 Docker Desktop의 메모리 부족을 피하기 위해 로컬 worker는
`AGENT_PARITY_MAX_WORKERS=2`가 기본값이다. Newman은 이미지 빌드 이후
`eu/ci/run-integration-tests.sh`를 통해 반드시 실행된다. Docker 내부의 Compose와 fixture 생성기가
같은 hotfix worktree를 사용하도록 `AGENT_NEWMAN_WORKSPACE_ROOT`는 호스트의
`.agent/runtime` 절대 경로를 가리키며 setup 스크립트가 자동으로 기록한다.

실행 후 `http://127.0.0.1:8080/`에서 운영 화면을 연다. 기존 `/ui`는 `/`로 이동한다. 실패 PR은
화면 진입 시 한 번 조회되며
“새로고침” 버튼으로 다시 조회한다. Grafana는 환경과 시작/종료 시각을 입력해 “관측 조회”를 눌러야
호출된다. 자연어 요청은 “해석하기”와 “이 해석으로 실행”을 순서대로 누르고, 분석 완료 후 표시되는
후보 중 하나를 선택해야 Draft PR 작업이 시작된다.

LLM 비용 가드는 역할별로 적용된다. 기본 입력/출력 token 상한은 triage `8000/1500`, reasoning
`16000/4000`, review `8000/1500`이며 `.env.local`의 `AGENT_AI_*_TOKENS` 값으로 더 낮출 수 있다.
Embabel action은 자동 재실행하지 않고 patch 생성만 정책상 최대 2회 재시도한다. provider 전송 오류는
비용 상한을 지키기 위해 자동 재시도하지 않으며 `AGENT_AI_PROVIDER_MAX_ATTEMPTS=1`이 기본값이다.
구조화 출력 변환도 `AGENT_AI_DATA_BINDING_MAX_ATTEMPTS=1`로 재요청하지 않는다. 형식 오류는 해당
분석 또는 hotfix를 실패/사람 검토 상태로 종료한 뒤 사용자가 명시적으로 다시 요청해야 한다.

Spring AI가 반환받은 실제 token usage는 prompt/completion 본문 없이 Prometheus metric으로 노출한다.

```bash
curl --silent http://127.0.0.1:8080/actuator/prometheus \
  | rg 'gen_ai|spring_ai'
```

LiteLLM gateway가 제공하는 사용량·비용 기록이 최종 과금 확인 기준이며, 로컬 Langfuse는
`./gradlew aiTest` 평가 score 기록에 사용한다.

```bash
docker compose --env-file .env.local logs --follow my-agent
docker compose --env-file .env.local down
```

## 검증

```bash
./gradlew check :app:aiMockTest
```

`check`에는 Error Prone, javac lint, Checkstyle, 단위 테스트와 아키텍처 테스트가 포함된다.
외부 LiteLLM/Langfuse 평가까지 한 번에 실행하려면 Docker를 켠 뒤 `./gradlew aiTest`를 사용한다.

2026-08-21 기준 위 명령은 통과했다. 실제 E2E에서는 FMS PR #1292의 hotfix commit에 대해 Gradle,
JaCoCo, Jib, Compose health와 Newman 20/20을 통과한 뒤 Draft PR #1295를 생성했다. 실제 credential을
쓰는 이 검증은 기본 `check`에 포함하지 않는다.

## API 문서

실행 후 `/swagger-ui.html` 또는 `/v3/api-docs`를 확인한다.
