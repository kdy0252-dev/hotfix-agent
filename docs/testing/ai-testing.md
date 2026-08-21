# AI 테스트 실행

## 테스트 계층

| Gradle 작업 | 외부 LLM | Docker | 목적 |
|---|---:|---:|---|
| `:app:aiMockTest` | 아니요 | 아니요 | Embabel `FakeOperationContext`로 응답과 프롬프트 계약을 빠르게 검증한다. |
| `:app:aiEvaluationTest` | LiteLLM 1회 | Langfuse v4 | LLM judge로 핫픽스 후보 품질을 채점하고 Langfuse에 score를 기록한다. |
| `aiTest` | 예 | 예 | 위 두 계층을 순서대로 한 번에 실행한다. |

일반 `test`, `architectureTest`, `check`에는 실 LLM 평가를 포함하지 않는다. 따라서 평소 빌드와
AI 비용이 연결되지 않는다.

현재 offline mock은 자연어 해석, 장애 후보 생성, hotfix patch author, 독립 patch reviewer를
검증한다. 장애 후보 생성은 evidence 타입에 따라 Jenkins 또는 observability triage action을 자동
선택하고 `triage` 모델 결과를 `reasoning` 모델에 typed artifact로 전달한다. 각 agent는 structured
output을 반환하고 tool group을 노출하지 않아야 한다.

## 사전 조건

- Docker Desktop이 실행 중이어야 한다.
- 프로젝트 루트의 `.env.local`에 `LITELLM_BASE_URL`, `LITELLM_API_KEY`,
  `LITELLM_MODEL`이 있어야 한다.
- 초기 설정이 필요하면 `./scripts/setup-env-local.zsh`를 실행한다.

## 한 번에 실행

```zsh
./gradlew aiTest
```

실행 과정에서 Gradle은 다음 작업을 수행한다.

1. `build/ai-test/langfuse.env`에 로컬 테스트 전용 임시 자격증명을 생성한다.
2. `infra/langfuse/compose.yml`의 Langfuse, PostgreSQL, ClickHouse, Redis, MinIO를 시작한다.
3. 인증된 Public API와 첫 score 쓰기를 확인해 Langfuse 쓰기 경로를 준비한다.
4. Embabel 모킹 테스트와 LiteLLM judge 평가를 실행한다.
5. judge 점수를 Langfuse의 `hotfix-candidate-quality` session score로 저장한다.
6. 평가 종료 후 컨테이너와 테스트 볼륨을 제거한다.

새 Docker 볼륨에서 실행하는 첫 score 쓰기는 Langfuse 내부 초기화 때문에 수분이 걸릴 수 있다.
Gradle은 최대 10분 동안 이 준비 작업을 기다린다.

Langfuse 화면을 직접 확인하려면 평가가 끝나기 전에 별도 터미널에서
`http://127.0.0.1:13000`을 연다. 자동 테스트 계정과 API 키는 실행 시 생성되는
`build/ai-test/langfuse.env`에 있으며 운영 자격증명이 아니다.

## 개별 실행

외부 연결 없이 Embabel 모킹 테스트만 실행한다.

```zsh
./gradlew :app:aiMockTest
```

결정적 안전성 검증은 별도로 다음 명령에 포함된다.

```zsh
./gradlew :app:test :app:architectureTest
```

- 금지된 `Jenkinsfile` 수정은 write 전에 거부된다.
- focused/review/parity/동일 commit 조건을 모두 만족해야 Draft PR port가 호출된다.
- parity 실패 시 Draft PR port 호출은 0회다.
- `src/main/java`에 Java `try` 문이 추가되면 `VavrTryArchTest`가 실패한다.

Langfuse 스택을 수동으로 시작하거나 종료할 수도 있다.

```zsh
./gradlew langfuseUp langfuseReady
./gradlew langfuseDown
```

`aiEvaluationTest`를 직접 실행해도 Langfuse를 먼저 시작하고 테스트 종료 후 정리한다.

```zsh
./gradlew :app:aiEvaluationTest
```

## 유지·확장 기준

- 각 Embabel action마다 성공, 거절 정책, 도구 실패 응답을 `FakeOperationContext`로 검증한다.
- 평가 fixture에는 Jenkins 로그, Grafana 관측 증거, 생성된 수정 계획, 테스트 결과, Draft PR
  정책 준수 여부를 포함한다.
- 자연어 API fixture에는 한국어/영어 동등 명령, 누락 필드, prompt injection, raw query와
  merge/deploy 우회 요청을 포함하고 확인 전 tool 호출 0회를 검증한다.
- judge 기준과 통과 임계값 변경은 평가 테스트 코드 리뷰를 거쳐야 한다.
- LLM 점수 하나만으로 PR 생성 권한을 부여하지 않는다. 결정적 정책 테스트와 Jenkins 상당
  검증이 모두 통과해야 한다.
