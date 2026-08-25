# CI/CD 및 관측 장애 핫픽스 에이전트 고객 요구사항 명세서

## 1. 문서 정보

| 항목 | 내용 |
| --- | --- |
| 문서명 | Customer Requirements Specification (CRS) |
| 시스템명 | Embabel 기반 FMS 핫픽스 에이전트 |
| 버전 | 1.3 |
| 상태 | 구현 기준선 |
| 기준일 | 2026-08-24 |
| 대상 저장소 | `autocrypt/fms` |
| 운영 담당 | BE팀 |

이 문서는 고객과 운영 담당자가 기대하는 시스템 결과, 사용 흐름, 안전 제약과 인수 조건을 정의한다.
세부 API, 데이터 모델과 기술 검증 방법은 [SRS](SRS.md)에서 정의한다.

## 2. 배경과 목적

FMS의 Jenkins 빌드 실패와 `eu-app`의 관측 이상을 사람이 일일이 수집하고 원인을 추적하는 시간을
줄이고자 한다. 시스템은 사용자가 지정한 사건만 분석하여 가능한 버그 후보를 제시하고, 사용자가
후보를 명시적으로 선택한 경우에만 안전한 범위에서 코드를 수정하고 Draft PR을 생성해야 한다.

이 시스템의 목적은 자동 배포가 아니라 다음 활동을 지원하는 것이다.

1. 실패 증거의 수집과 요약
2. 원인 후보와 관련 소스 위치 제시
3. 사용자 선택을 통한 수정 대상 확정
4. 제한된 코드 수정과 검증
5. 검토 가능한 Draft PR 생성

## 3. 이해관계자

| 이해관계자 | 관심 사항 |
| --- | --- |
| BE팀 | 장애 분석 정확성, 수정 범위, 검증 결과, 운영 안전성 |
| API 사용자/시연자 | 간단한 분석 요청, 후보 목록 확인, 명시적 후보 선택 |
| PR 검토자 | 변경 근거, 변경량, 테스트 결과, 재현 가능한 증거 |
| DevOps/SRE | Jenkins 및 관측 시스템에 대한 읽기 전용 접근 보장 |

PR reviewer는 시스템이 자동 지정하지 않는다. Draft PR 생성 후 필요한 검토자는 사람이 결정한다.

## 4. 시스템 범위

### 4.1 포함 범위

- Jenkins `FMS-EU` build 실패 분석
- 지정 시간 범위와 환경의 Grafana datasource를 통한 `eu-app` 관측 분석
- Loki 로그, Tempo trace, Prometheus metric의 제한된 읽기
- `autocrypt/fms`의 branch 또는 open PR을 기준으로 한 소스 분석
- AI 기반 버그 후보 목록 생성
- 사용자가 선택한 후보에 대한 코드 수정과 로컬 검증
- `agent/hotfix/*` branch 및 Bitbucket Draft PR 생성
- 명시적 요청에 따른 Jenkins PR build 상태 확인
- 실패 PR, 운영 신호, 자연어 명령과 Draft 진행 상태를 제공하는 로컬 운영 UI

### 4.2 제외 범위

- 자동 polling 또는 주기적 사건 탐지
- 자동 merge, approve, tag, release, deploy, rollback
- Kubernetes resource 변경이나 Pod 재시작
- Grafana dashboard, Alert rule, contact point 변경
- 운영 DB 데이터 또는 schema 변경
- migration, secret, `Jenkinsfile`, 배포 manifest 변경
- Slack, Jira 등 외부 시스템 자동 통지
- 기존 PR source branch에 직접 push

## 5. 사용자 흐름

### 5.1 Jenkins 실패 분석

1. 사용자가 Jenkins job path, build number와 분석 기준 branch 또는 PR 번호를 API로 전달한다.
2. 시스템이 실패 증거와 관련 소스를 분석한다.
3. 시스템이 버그 후보 목록을 반환한다.
4. 사용자가 후보 하나를 선택한다.
5. 시스템이 선택한 source에서 hotfix branch를 만들고 Jenkins 동등 검증 전체 성공 후 Draft PR을 생성한다.

### 5.2 Grafana 관측 분석

1. 사용자가 탐색 시작/종료 시각, 환경과 분석 기준 branch 또는 PR 번호를 API로 전달한다.
2. 시스템이 해당 시간 범위의 `eu-app` metric, trace와 log를 읽는다.
3. 시스템이 버그 후보 목록을 반환한다.
4. 이후 과정은 Jenkins 분석과 동일하게 사용자의 후보 선택 후에만 진행한다.

### 5.3 자연어 요청

1. 사용자가 한국어 또는 영어로 원하는 분석·조회·선택·CI 확인 작업을 입력한다.
2. 시스템이 문장을 지원되는 구조화 명령으로 해석하고 실행 전 미리보기를 반환한다.
3. 필수 식별자나 범위가 없거나 표현이 모호하면 실행하지 않고 필요한 값을 질문한다.
4. 사용자가 해석 결과의 버전과 명령 hash를 확인해 명시적으로 실행한다.
5. 실행된 명령은 기존 구조화 API와 같은 후보 선택, 검증, Draft PR 가드를 통과한다.

### 5.4 운영 UI

1. 사용자는 실패한 open PR branch와 Jenkins build를 목록에서 확인하고 원본 화면으로 이동한다.
2. 사용자는 환경과 시작/종료 시각을 제출하여 설정된 대상 서비스의 운영 알람과 Trace를 조회한다.
3. 사용자는 목록의 분석 버튼 또는 자연어 입력으로 해석을 요청하고, 해석 결과를 확인해 실행한다.
4. 분석 완료 후 사용자가 후보를 선택해야만 Draft PR workflow가 시작된다.
5. 사용자는 로컬 상태를 통해 patch, 검증, Draft PR과 CI 진행 단계를 확인한다.

## 6. 고객 기능 요구사항

### 6.1 분석 요청

| ID | 요구사항 |
| --- | --- |
| CRS-FUN-001 | 시스템은 사용자가 API로 요청한 사건만 분석해야 하며 Jenkins 또는 Grafana를 자동 polling하지 않아야 한다. |
| CRS-FUN-002 | 시스템은 Jenkins job path와 build number를 사용하여 `FMS-EU` 실패 build를 분석할 수 있어야 한다. |
| CRS-FUN-003 | 시스템은 Grafana 관측 분석 시 탐색 시작/종료 시각과 `DEV`, `QA`, `PROD` 환경을 입력받아야 한다. |
| CRS-FUN-004 | Grafana 관측 분석 대상은 항상 `eu-app`이어야 하며 사용자가 다른 service를 지정할 수 없어야 한다. |
| CRS-FUN-005 | Grafana 관측 시간 범위는 사용자가 입력해야 하며 시작 시각은 종료 시각보다 앞서야 하고 최대 31일을 넘지 않아야 한다. |
| CRS-FUN-006 | 사용자는 분석 기준으로 존재하는 Bitbucket branch 또는 open PR 번호를 지정할 수 있어야 한다. |

### 6.2 후보 목록과 선택

| ID | 요구사항 |
| --- | --- |
| CRS-FUN-007 | 시스템은 분석 결과를 구분 가능한 버그 후보 목록으로 제공해야 하며 후보가 없으면 빈 목록과 사유를 반환해야 한다. |
| CRS-FUN-008 | 각 후보는 제목, 원인, 신뢰도, 관련 소스 위치, 근거, 반대 근거, 수정 가능 여부와 검증 방향을 포함해야 한다. |
| CRS-FUN-009 | 시스템은 후보 선택 전에는 worktree, branch, patch 또는 PR을 생성하지 않아야 한다. |
| CRS-FUN-010 | 사용자는 후보 ID와 분석 버전을 지정하여 후보 하나를 명시적으로 선택할 수 있어야 한다. |
| CRS-FUN-011 | 분석 후 source commit이 변경된 후보는 선택할 수 없어야 하며 재분석을 요구해야 한다. |

### 6.3 코드 수정과 PR

| ID | 요구사항 |
| --- | --- |
| CRS-FUN-012 | branch를 기준으로 선택하면 해당 원격 branch commit에서 hotfix branch를 만들고 같은 branch를 Draft PR 대상으로 사용해야 한다. |
| CRS-FUN-013 | PR 번호를 기준으로 선택하면 기존 open PR의 source commit에서 hotfix branch를 만들고 기존 PR source branch를 Draft PR 대상으로 사용해야 한다. |
| CRS-FUN-014 | 시스템은 기존 branch 또는 PR source branch에 직접 push하지 않아야 한다. |
| CRS-FUN-015 | 생성 branch 이름은 `agent/hotfix/*` 형식이어야 한다. |
| CRS-FUN-016 | 시스템은 수정된 동일 commit에 대해 Jenkins PR pipeline의 배포 제외 전 검증 단계와 동등한 Gradle, JaCoCo, Jib, Compose health와 Newman 검증을 모두 통과한 경우에만 Bitbucket Draft PR을 생성해야 한다. |
| CRS-FUN-017 | Draft PR에는 분석 근거, source, 변경 범위, 테스트 결과, 운영 담당 `BE팀`과 사람 검토 필요 문구가 포함되어야 한다. |
| CRS-FUN-018 | 시스템은 PR reviewer를 자동 지정하지 않아야 한다. |
| CRS-FUN-019 | 시스템은 명시적인 CI 상태 확인 요청이 있을 때만 Jenkins PR build 상태를 갱신해야 한다. |
| CRS-FUN-020 | 시스템은 한국어 또는 영어 자연어를 지원되는 작업의 구조화된 미리보기로 변환할 수 있어야 한다. |
| CRS-FUN-021 | 자연어 해석은 실행과 분리되어야 하며 사용자가 해석 버전과 명령 hash를 확인한 뒤에만 실행할 수 있어야 한다. |
| CRS-FUN-022 | 자연어로 시작한 작업도 기존 구조화 API와 동일한 analysis, candidate selection, Jenkins 동등 검증과 Draft PR 흐름을 사용해야 한다. |
| CRS-FUN-023 | 자연어 요청에 필수 식별자·시간 범위·환경·source가 없거나 여러 해석이 가능하면 시스템은 실행하지 않고 명확화 항목을 반환해야 한다. |
| CRS-FUN-024 | 시스템은 실패한 open PR의 branch, commit, Jenkins build와 Bitbucket/Jenkins 링크를 SSR UI에 표시해야 한다. |
| CRS-FUN-025 | 시스템은 사용자가 제출한 환경·시간 범위의 대상 서비스 Grafana 알람과 Trace를 목록으로 표시하고 Grafana 링크를 제공해야 한다. |
| CRS-FUN-026 | UI 자연어 입력은 해석 확인과 후보 선택 단계를 거쳐야 하며 한 번의 입력으로 Draft PR을 직접 생성하지 않아야 한다. |
| CRS-FUN-027 | UI는 Draft PR workflow의 현재 단계와 생성된 Bitbucket/Jenkins 링크를 표시해야 한다. |
| CRS-FUN-028 | 실패한 workflow는 현재 세부 단계, 실패 코드, 복구 방법과 실행된 검증 결과를 페이지 재로드 후에도 표시해야 한다. |
| CRS-FUN-029 | 수정 branch가 생성된 경우 사용자는 해당 branch를 사람 검토용으로 게시하고 직접 수정 commit을 push할 수 있어야 한다. |
| CRS-FUN-030 | 사람이 push한 commit도 자동 patch와 동일한 변경 정책, 테스트, AI 검토와 Jenkins 동등성 검증을 다시 통과해야 Draft PR을 생성할 수 있어야 한다. |

## 7. 고객 안전 요구사항

| ID | 요구사항 |
| --- | --- |
| CRS-SAF-001 | 운영 및 비운영 관측 시스템에 대한 모든 접근은 읽기 전용이어야 한다. |
| CRS-SAF-002 | 자동 변경은 최대 10개 파일과 총 500 changed lines를 넘지 않아야 한다. |
| CRS-SAF-003 | 코드 수정 재시도는 최대 2회여야 한다. |
| CRS-SAF-004 | migration, secret, `Jenkinsfile`, 배포 manifest는 자동 변경할 수 없어야 한다. |
| CRS-SAF-005 | 인프라, IAM, 인증서, DB 데이터, schema 또는 수동 복구가 필요한 사건은 사람 검토로 전환해야 한다. |
| CRS-SAF-006 | secret, token, 개인정보와 식별 가능한 운영 데이터는 AI 입력과 PR 본문에서 마스킹되어야 한다. |
| CRS-SAF-007 | PromQL, LogQL, TraceQL과 shell command를 사용자 또는 LLM의 자유 형식 문자열로 실행하지 않아야 한다. |
| CRS-SAF-008 | merge, tag, release, deploy 관련 기능은 시스템에 제공하지 않아야 한다. |
| CRS-SAF-009 | Jenkins 동등 검증을 일부 생략했거나 로컬 환경에서 재현할 수 없으면 Draft PR을 생성하지 않고 사람 검토로 전환해야 한다. |
| CRS-SAF-010 | 자연어는 shell, URL, PromQL, LogQL, TraceQL 또는 tool 인자로 직접 실행하지 않고 폐쇄된 명령 schema로만 변환해야 한다. |
| CRS-SAF-011 | 자연어 지시로 repository, 환경, `eu-app` 범위, 금지 경로, 변경 한도, Draft-only, merge/tag/deploy 금지를 완화할 수 없어야 한다. |
| CRS-SAF-012 | 자연어에 포함된 prompt injection 또는 정책 우회 지시는 데이터로 취급하고 실행 권한이나 agent tool 구성을 바꾸지 않아야 한다. |

## 8. 품질 요구사항

| ID | 요구사항 |
| --- | --- |
| CRS-QUA-001 | 동일 요청을 반복해도 분석, branch 또는 PR이 중복 생성되지 않아야 한다. |
| CRS-QUA-002 | 모든 후보와 PR은 사용한 외부 증거와 source commit까지 추적 가능해야 한다. |
| CRS-QUA-003 | 외부 호출 또는 AI 처리가 오래 걸리더라도 사용자는 분석 및 hotfix 진행 상태를 조회할 수 있어야 한다. |
| CRS-QUA-004 | 프로세스 재시작 후에도 API로 시작된 미완료 작업을 안전하게 재개할 수 있어야 한다. |
| CRS-QUA-005 | 시스템은 로컬 Mac에서 실행되며 FMS 사용자의 기존 working tree를 변경하지 않아야 한다. |
| CRS-QUA-006 | AI 모델 교체가 안전 정책, Git 작업, 검증 또는 PR 발급 권한을 변경해서는 안 된다. |
| CRS-QUA-007 | 각 에이전트는 직접 사용하는 스킬과 툴을 각각 최대 5개로 제한하고, 더 많은 역할이 필요하면 목적이 분명한 하위 에이전트로 분리해야 한다. |

## 9. 운영 및 환경 제약

| 항목 | 요구사항 |
| --- | --- |
| 실행 위치 | 개발자 로컬 Mac |
| 소스 저장소 | `autocrypt/fms` |
| CI | Jenkins `FMS-EU` |
| 관측 진입점 | `https://prod-grafana.autocrypt-fms.io` 및 Grafana datasource proxy |
| AI | Embabel과 LiteLLM 호환 API |
| 자격증명 | `.env.local` 또는 Passwords 앱을 통해 주입하고 Git에 저장하지 않음 |
| TLS 시연 예외 | Jenkins와 Grafana에 한해 TLS 검증 비활성화를 명시적으로 허용 |
| PR 상태 | Draft만 허용 |

## 10. 인수 기준

| ID | 인수 시나리오 | 기대 결과 |
| --- | --- | --- |
| CRS-ACC-001 | API 호출 없이 시스템을 실행한다. | Jenkins와 Grafana 외부 호출이 발생하지 않는다. |
| CRS-ACC-002 | 실패 Jenkins build와 유효한 branch를 분석한다. | 코드 변경 없이 버그 후보 목록이 생성된다. |
| CRS-ACC-003 | 30분 범위와 `PROD`를 지정하여 관측 분석한다. | 지정 범위 내 `fms-eu-prod`의 `eu-app` 증거만 후보 생성에 사용된다. |
| CRS-ACC-004 | 후보를 선택하지 않는다. | worktree, hotfix branch와 PR이 생성되지 않는다. |
| CRS-ACC-005 | 유효한 후보와 최신 분석 버전을 선택한다. | 수정 commit의 Gradle, JaCoCo, Jib, Compose health와 Newman 검증이 전부 성공한 후에만 Draft PR이 생성된다. |
| CRS-ACC-006 | 분석 후 기준 branch commit을 변경하고 기존 후보를 선택한다. | 선택이 거절되고 재분석을 요구한다. |
| CRS-ACC-007 | 금지 파일 또는 변경 한도 초과 patch를 제안한다. | 변경과 PR 생성이 차단되고 사람 검토 결과가 반환된다. |
| CRS-ACC-008 | 동일 idempotency key로 분석과 선택을 반복한다. | 기존 analysis/hotfix 결과를 반환하고 중복 PR을 만들지 않는다. |
| CRS-ACC-009 | 명시적인 CI refresh API를 호출한다. | 해당 hotfix PR의 Jenkins 상태를 한 번 조회하여 결과를 갱신한다. |
| CRS-ACC-010 | Jenkins 동등 검증 단계 하나를 실패시키거나 실행 불가로 만든다. | Draft PR 없이 사람 검토 상태와 실패 단계가 반환된다. |
| CRS-ACC-011 | 자연어로 Jenkins 실패 분석을 요청하고 해석 결과를 확인한다. | 실행 전 구조화 미리보기가 반환되고 확인 전 외부 조회가 발생하지 않는다. |
| CRS-ACC-012 | 모호한 자연어 또는 정책 우회 문구를 전달한다. | 명확화 또는 거절 결과가 반환되고 tool, Git write와 PR 생성이 발생하지 않는다. |

## 11. 운영 연결점 상태

다음 운영 연결점은 확인을 완료했다.

1. Grafana read-only service account token과 datasource query 권한
2. Grafana Loki, Tempo, Prometheus datasource UID
3. `DEV`, `QA`, `PROD` namespace와 `eu-app`의 공통 `service_name` label mapping

2026-08-21에 실제 Jenkins 실패 사례 FMS PR #1292를 shadow fixture로 사용했다. 생성된 hotfix commit
`d57a84a470878933ef23f370a01b034052394653`은 네 parity stage와 Newman 20/20을 통과했고 reviewer
없는 Bitbucket Draft PR #1295로 발행됐다. PR Jenkins build가 시작된 것까지 확인했으며 최종 SUCCESS는
이 기준선에서 확정하지 않는다. 이 확인은 자동 변경 권한이나 본 문서의 안전 기준을 완화하지 않는다.
