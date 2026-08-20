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

## 검증

```bash
./gradlew :app:test
./gradlew :app:architectureTest
./gradlew :app:checkstyleMain
./gradlew :app:aiMockTest
./gradlew :app:build
```

`build`에는 Error Prone, javac lint, Checkstyle, 단위 테스트와 아키텍처 테스트가 포함된다.
외부 LiteLLM/Langfuse 평가까지 한 번에 실행하려면 Docker를 켠 뒤 `./gradlew aiTest`를 사용한다.

## API 문서

실행 후 `/swagger-ui.html` 또는 `/v3/api-docs`를 확인한다.
