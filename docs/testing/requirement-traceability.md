# 구현 및 검증 추적표

이 문서는 CRS/SRS의 정본을 대체하지 않고, 현재 코드와 자동 검증 위치를 빠르게 찾기 위한 색인이다.
실제 운영 credential을 사용하는 shadow 검증은 기본 `check`에 포함하지 않는다.

| 요구사항 묶음 | 구현 경계 | 자동 검증 |
| --- | --- | --- |
| `SRS-API-001~006` | 구조화 controller, request hash, 비동기 executor, scheduler 없음 | `*ControllerContractTest`, `IncidentAnalysisServiceTest` |
| `SRS-JEN-001~005` | 실패 build metadata preflight, bounded console/test 수집 | `JenkinsRestAdapterTest`, `IncidentControllerContractTest` |
| `SRS-OBS-001~011` | 고정 EU app query, Prometheus/Loki/Tempo/alert 경계 | `GrafanaObservabilityAdapterTest`, `GrafanaEvidenceBoundaryTest` |
| `SRS-SRC-001~007` | branch/open PR commit 고정, selection/publish 전 freshness 검사 | Bitbucket source adapter tests |
| `SRS-CAN-001~007` | triage → reasoning typed artifact, 실제 evidence provenance 고정 | `IncidentAnalysisAgentAiMockTest`, 상태 persistence test |
| `SRS-SEL-001~006` | version/TTL/eligibility/evidence/idempotency gate | `HotfixSelectionServiceTest`, controller contract test |
| `SRS-GIT-001~007` | 전용 worktree, 사전·사후 path/10 files/500 lines gate | `LocalGitPatchWorkspaceAdapterTest` |
| `SRS-VER-001~014` | focused retry, 독립 review, Jenkins parity profile/commit binding | workflow, parity profile, provenance tests |
| `SRS-PR-001~007` | parity 재검증, hotfix branch push, reviewer 없는 Draft, 명시적 CI refresh | PR adapter 및 hotfix query tests |
| `SRS-STA-001~008` | 분리 PostgreSQL schema, 관계형 순서·분석 원문 보존, 재기동 시 분석·로컬 hotfix 재제출, Draft PR 비중복 | Liquibase/JPA mapping, `IncidentAnalysisServiceTest`, `HotfixRecoveryServiceTest`, worktree recreation test |
| `SRS-NL-001~014` | 폐쇄 intent, 2단계 확인, hash/version/TTL, 기존 typed gateway 재사용 | 자연어 controller/service/module adapter tests와 AI mock |
| `SRS-UI-001~017` | dashboard vertical slice, 카드 단위 SSR 갱신, 분석 버튼 진행·중복 재분석, 세부 단계·실패·검증 이력, 사람 branch 수정과 재검증 | `DashboardControllerTest`, `GuardedHotfixWorkflowAdapterTest`, `LocalGitPatchWorkspaceAdapterTest`, JPA mapping 및 architecture tests |
| `SRS-NFR-SEC-*` | redaction, loopback, AI prompt/completion observation 비활성화 | redactor 및 application configuration tests |
| `SRS-NFR-PER-*` | 역할별 입출력 budget, FIRE_ONCE action, provider retry와 token metric | evidence boundary, `LlmPromptBudgetTest`, `AgentCapabilityArchTest`, configuration tests |
| `SRS-NFR-MNT-*` | port 경계, capability 최대 5, 부모-자식 소유권, Vavr Try | 전체 `*ArchTest` |

기본 회귀 명령은 `./gradlew check :app:aiMockTest`다. 외부 LLM과 로컬 Langfuse까지 포함한 평가는
Docker 실행 후 `./gradlew aiTest`로 분리한다.

## 실환경 E2E 기준선

| 기준일 | 입력 | 결과 |
| --- | --- | --- |
| 2026-08-21 | FMS PR #1292 Jenkins 실패 | hotfix commit `d57a84a470878933ef23f370a01b034052394653` |
| 2026-08-21 | `JENKINS_PR_PARITY` | Gradle, JaCoCo, Jib, Compose health 네 stage 성공 |
| 2026-08-21 | Newman | bootstrap과 본 collection 20/20 성공: admin 11, driving 9 |
| 2026-08-21 | Bitbucket | reviewer 없는 Draft PR #1295 생성 |
| 2026-08-21 | Jenkins PR job | PR-1295 #1 시작 확인, 최종 결과 미확정 |

이 표는 운영 credential을 사용하는 수동 shadow 기록이며 기본 자동 테스트 통과를 대신하지 않는다.
