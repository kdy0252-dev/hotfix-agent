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
| `SRS-STA-001~005` | schema version JSON, 임시 파일 + atomic move, 재요청 복구 | persistence 및 service idempotency tests |
| `SRS-NL-001~014` | 폐쇄 intent, 2단계 확인, hash/version/TTL, 기존 typed gateway 재사용 | 자연어 controller/service/module adapter tests와 AI mock |
| `SRS-NFR-SEC-*` | redaction, loopback, AI prompt/completion observation 비활성화 | redactor 및 application configuration tests |
| `SRS-NFR-PER-*` | 응답/LLM 입력 budget, 중요도 순 prompt 구성 | evidence boundary와 `LlmPromptBudgetTest` |
| `SRS-NFR-MNT-*` | port 경계, capability 최대 5, 부모-자식 소유권, Vavr Try | 전체 `*ArchTest` |

기본 회귀 명령은 `./gradlew clean check :app:aiMockTest`다. 외부 LLM과 로컬 Langfuse까지 포함한
평가는 Docker 실행 후 `./gradlew aiTest`로 분리한다.
