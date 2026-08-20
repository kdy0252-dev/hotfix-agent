# 문서 카탈로그

| 카테고리 | 정본 문서 | 목적 |
| --- | --- | --- |
| 요구사항 | [CRS](requirements/CRS.md), [SRS](requirements/SRS.md) | 고객 요구와 검증 가능한 소프트웨어 요구사항 |
| 설계 | [시스템 아키텍처](design/system-architecture.md), [핫픽스 에이전트 설계](design/observability-hotfix-agent.md), [자연어 API 가드레일](design/natural-language-api-guardrails.md) | 구조, 데이터 흐름, 정책과 구현 단계 |
| API | [Hotfix Agent API](api/hotfix-agent-api.md) | HTTP 계약과 SRS 추적성 |
| 에이전트 | [에이전트 카탈로그](agents/agent-catalog.md), [실행 조건](agents/execution-conditions.md) | agent/subagent 책임과 safety gate |
| 역량 | [스킬](capabilities/skills.md), [툴](capabilities/tools.md) | 추론 역량과 외부 I/O 권한 |
| 가이드 | [개발 가이드](guides/development.md) | 로컬 실행과 검증 방법 |
| 테스트 | [AI 테스트 실행](testing/ai-testing.md) | Embabel 모킹, LiteLLM judge, Langfuse 로컬 실행 |
| 추적성 | [구현 및 검증 추적표](testing/requirement-traceability.md) | SRS 묶음별 코드와 자동 테스트 위치 |

## 변경 순서

1. 사용자 요구가 바뀌면 CRS를 수정한다.
2. 검증 가능한 동작과 제약을 SRS ID로 수정한다.
3. HTTP 계약 변경은 API 문서에서 해당 SRS ID와 함께 수정한다.
4. 책임 또는 실행 순서 변경은 에이전트/실행 조건 문서를 수정한다.
5. 추론 schema 변경은 스킬, 외부 권한 변경은 툴 문서를 수정한다.
6. 구현 구조와 단계는 설계 문서에 반영한다.

구현 코드나 test가 요구사항을 참조할 때는 설명 문장 대신 안정적인 `SRS-*` ID를 사용한다.
