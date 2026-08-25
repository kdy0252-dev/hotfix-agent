# CI/CD 핫픽스 에이전트 작업 문서

아래 순서로 문서를 사용한다.

1. [운영자 사전 설정 가이드](guides/260819-01-cicd-hotfix-agent-operator-setup.md)
   - 운영 정책 결정
   - Bitbucket/Jenkins 최소 권한 자격증명 생성
   - Bitbucket/Jenkins 연결 확인 절차만 사용
   - Kubernetes 배포와 EKS 내부 실행 절차는 현재 로컬 실행 방식에 적용하지 않음
2. [운영 관측 핫픽스 에이전트 설계](plans/260820-01-observability-hotfix-agent-design.md)
   - 로컬 실행과 prod 읽기 전용 경계
   - API로 전달된 Jenkins/Grafana 장애의 Loki/Tempo/Prometheus 증거 수집
   - 후보 목록 조회와 branch/PR 기준 명시적 선택 후 Draft PR 생성
   - Embabel action/goal, 안전 정책, shadow 운영과 구현 순서
3. [초기 구현 계획](plans/260819-01-cicd-hotfix-agent-implementation.md)
   - 초기 검토 자료이며 실행 위치와 관측 범위는 2번의 최신 설계를 우선 적용
   - Embabel action/goal과 multi-model 설계
   - domain model과 VO 설계
   - Jenkins/Loki/Prometheus/Bitbucket adapter
   - 안전한 patch/verification/Draft PR 흐름
   - 단계별 test와 완료 기준
4. [LiteLLM API Key 확인](guides/260820-01-litellm-api-key-check.md)
   - LiteLLM 키 인증과 사용 가능한 모델 목록 확인
   - OpenAI 호환 Chat Completions API 실제 호출 확인
5. [OpenAI API Key 확인](guides/260820-02-openai-api-key-check.md)
   - OpenAI 키 인증과 사용 가능한 모델 목록 확인
   - Responses API 실제 호출 확인
6. [Bitbucket Access Token 로컬 설정](guides/260820-03-bitbucket-access-token-setup.md)
   - Passwords 앱 보관과 로컬 환경변수 설정
   - `autocrypt/fms` 저장소 및 Pull Request 읽기 권한 확인
7. [Jenkins API Token 로컬 설정](guides/260820-04-jenkins-api-token-setup.md)
   - Passwords 앱 보관과 로컬 환경변수 설정
   - `FMS-EU` Job 및 console log 읽기 권한 확인
8. [Agent 구성과 UI API 호출 경로](guides/260825-01-agent-ui-api-call-flow.md)
   - 현재 Embabel agent 5종의 역할, action, goal, skill 정리
   - Jenkins/Grafana/Bitbucket/Git/Gradle typed tool 구성
   - 운영 UI API에서 각 agent까지 이어지는 실제 호출 경로
   - 후보 카드와 AI 챗봇의 선택적 수정 방향 전달·저장 경로

## 진행 원칙

- 운영자 가이드 5절의 정책 결정값을 먼저 채운다.
- secret 값은 이 디렉토리나 Git에 기록하지 않는다.
- 구현은 계획의 Phase 0부터 순서대로 진행한다.
- `IssueResolved`는 Draft PR 생성뿐 아니라 Jenkins PR build 성공까지 확인된 상태다.
- merge, tag, deploy는 이 에이전트의 범위가 아니다.

## Git 상태

현재 project `.gitignore`는 `.agent/`를 기본 제외한다. 다만 이 README와 기능별 agent 호출 흐름
가이드는 팀 공유 대상으로 명시적으로 Git에 포함한다. secret, runtime 상태, 개인별 설정 파일은
계속 포함하지 않는다.
