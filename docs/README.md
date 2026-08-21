# 문서 카탈로그

이 디렉터리는 현재 구현의 요구사항, 설계와 검증 계약을 관리한다. 초기 조사와 계획 이력은
`.agent/`에 남아 있지만 현재 동작의 정본으로 사용하지 않는다.

| 카테고리 | 정본 문서 | 현재 다루는 내용 |
| --- | --- | --- |
| 요구사항 | [CRS](requirements/CRS.md), [SRS](requirements/SRS.md) | 고객 요구, 안전 정책, 검증 가능한 소프트웨어 요구사항과 구현 상태 |
| 설계 | [시스템 아키텍처](design/system-architecture.md), [핫픽스 에이전트 설계](design/observability-hotfix-agent.md), [자연어 가드레일](design/natural-language-api-guardrails.md) | 실제 package, typed workflow, 상태·권한·가드레일 |
| API | [Hotfix Agent API](api/hotfix-agent-api.md) | 구현된 HTTP endpoint, request/response와 SRS 매핑 |
| 에이전트 | [에이전트 카탈로그](agents/agent-catalog.md), [실행 조건](agents/execution-conditions.md) | 실제 Embabel agent 4개와 결정론적 workflow 조건 |
| 역량 | [스킬](capabilities/skills.md), [툴](capabilities/tools.md) | agent capability manifest와 application adapter 권한 |
| 가이드 | [개발 가이드](guides/development.md) | `.env.local`, 호스트/Docker 실행, parity와 관측 방법 |
| 테스트 | [AI 테스트 실행](testing/ai-testing.md) | offline Embabel mock, LiteLLM judge와 Langfuse |
| 추적성 | [구현 및 검증 추적표](testing/requirement-traceability.md) | SRS별 구현 클래스, 테스트와 실환경 검증 상태 |

## 현재 기준

- 기준일: 2026-08-21
- 실행 위치: 개발자 로컬 Mac 또는 프로젝트 Docker Compose
- 저장소/CI: `autocrypt/fms`, Jenkins `FMS-EU`
- 관측 범위: `DEV`, `QA`, `PROD`의 EU `app`
- 쓰기 범위: `agent/hotfix/*` branch와 reviewer 없는 Bitbucket Draft PR
- 필수 발행 게이트: Gradle verification, coverage, Jib image build, Compose health/Newman
- 검증 사례: FMS PR #1292 분석 → Newman 20/20 성공 → Draft PR #1295 생성

## 변경 순서

1. 사용자 요구 변경은 CRS에 반영한다.
2. 검증 가능한 동작과 제약은 SRS ID로 반영한다.
3. HTTP 계약은 API 문서와 controller contract test를 함께 수정한다.
4. agent 책임이나 실행 조건은 agent/condition 문서를 수정한다.
5. 추론 schema는 skill, 외부 권한은 tool 문서를 수정한다.
6. 실제 package와 runtime 흐름은 설계 문서에 반영한다.
7. 마지막으로 추적표와 루트 README의 상태를 갱신한다.
