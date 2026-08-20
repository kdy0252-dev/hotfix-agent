# my-agent

Spring convention을 사용하는 `agent` Spring Boot 프로젝트다.

## 프로젝트 구조

```text
my-agent/
├── build.gradle.kts              # 루트 공통 태스크
├── settings.gradle.kts           # 프로젝트명, 저장소, app 모듈 등록
├── gradle.properties             # Gradle 캐시·병렬 빌드 설정
├── gradle/
│   ├── libs.versions.toml         # 라이브러리와 플러그인 버전
│   └── wrapper/                   # 고정된 Gradle 실행 환경
├── docs/
│   ├── requirements/              # CRS·SRS
│   ├── design/                    # 시스템·에이전트 설계
│   ├── api/                       # HTTP 계약과 SRS 매핑
│   ├── agents/                    # agent/subagent와 실행 조건
│   ├── capabilities/              # 스킬과 툴 권한
│   └── guides/                    # 실행·검증·환경 설정
├── build-logic/
│   ├── src/main/kotlin/           # Spring convention plugin 구현
│   └── src/main/resources/
│       └── checkstyle/            # 공통 Checkstyle 규칙
└── app/
    ├── build.gradle.kts           # agent 의존성과 검증 태스크
    └── src/
        ├── main/
        │   ├── java/com/example/myagent/
        │   │   ├── MyAgentApplication.java
        │   │   └── global/annotation/ # 아키텍처 경계 annotation
        │   └── resources/
        │       └── application.yml    # Spring 실행 설정
        └── test/java/com/example/myagent/
            ├── *ArchTest.java         # ArchUnit/jMolecules 규칙
            └── MyAgentApplicationTest.java
```

모든 preset은 Checkstyle, Error Prone, OpenRewrite, jMolecules/ArchUnit,
JaCoCo 및 SpringDoc 설정을 포함한다.

## 검증

```bash
./gradlew :app:build
./gradlew :app:architectureTest
./gradlew :app:checkstyleMain
```

## 실행

최초 한 번 `.env.local`을 생성한다.

```zsh
./scripts/setup-env-local.zsh
```

스크립트가 Bitbucket, Jenkins, Grafana, LiteLLM, OpenAI 자격증명을 숨김 입력으로 받고 프로젝트 루트의
`.env.local`에 저장한다. 파일은 Git에서 제외되며 소유자만 읽을 수 있도록 권한 `600`으로
생성된다. Grafana의 Loki, Prometheus, Tempo datasource UID는 API에서 자동 탐색해 함께 저장한다.
설정할 변수 목록은 [.env.local.example](.env.local.example)에서 확인할 수 있다.

이후 애플리케이션을 실행한다.

```zsh
./scripts/run-local.zsh
```

`run-local.zsh`는 `.env.local`을 환경변수로 불러온다. 파일이 없거나 필수값이 비어 있으면 다음
값을 터미널에서 다시 입력받는다.

- Bitbucket Repository Access Token
- Jenkins username과 API Token
- Grafana service account token
- LiteLLM URL, API Key, model

OpenAI API Key도 `.env.local`에 저장되지만 현재 Embabel 실행은 LiteLLM을 사용하므로 직접
OpenAI 호출을 추가하기 전까지는 애플리케이션에서 소비하지 않는다.

토큰과 API Key 입력은 터미널에 표시되지 않는다. 로컬 프로필은 `autocrypt/fms`, Jenkins
`FMS-EU`, LiteLLM `https://aigw.autocrypt.co.kr`를 기본값으로 사용한다. Jenkins TLS 인증서
검증은 시연 환경에 한해 비활성화한다. 기존 `.env.local`에 Grafana datasource UID가 없으면 실행
스크립트가 시작 전에 자동 탐색한다. 기본 실행 모드는 `REPORT_ONLY`, API bind 주소는
`127.0.0.1`, 분석 결과 TTL은 24시간이다.

스크립트는 Gradle daemon에 자격증명이 남지 않도록 `--no-daemon`으로 애플리케이션을 실행한다.
`.env.local`은 평문 secret 파일이므로 외부 공유, 메신저 첨부 또는 Git 추가를 금지한다.

`server`와 `batch` 유형의 기본 H2 설정은 로컬 실행용이다. PostgreSQL로 전환할 때는
`db/changelog`에 실제 Liquibase migration을 추가하고 JPA 스키마 정책을 `validate`로 변경한다.

애플리케이션 실행 후 OpenAPI UI는 기본적으로 `/swagger-ui.html`에서 확인한다.
