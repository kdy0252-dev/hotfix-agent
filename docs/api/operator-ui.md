# 운영 UI 계약

## 1. 진입점과 원칙

운영 UI는 `GET /`에서 제공하는 Thymeleaf/HTMX SSR 화면이다. JSON API를 대체하지 않으며 기존
application use case의 사용자용 진입점이다. 자연어 한 번으로 수정 권한을 부여하지 않고 해석 확인과
후보 선택을 유지한다.

관련 요구사항: `SRS-UI-001~012`, `SRS-NL-001~014`, `SRS-SEL-001~006`.

## 2. SSR endpoint

| Method | Path | 목적 | 외부 조회 |
| --- | --- | --- | --- |
| `GET` | `/` | 화면 shell과 입력 기본값 | 없음 |
| `GET` | `/ui` | 기존 주소를 `/`로 redirect | 없음 |
| `GET` | `/ui/fragments/pull-requests` | 실패한 open PR branch 목록 | Jenkins, Bitbucket 각 1회 |
| `POST` | `/ui/analyses/jenkins` | 선택한 실패 PR의 typed Jenkins 분석 접수 | Jenkins, Bitbucket |
| `GET` | `/ui/fragments/observability` | 환경·시간 범위의 알람과 Trace | Grafana 1회 요청 흐름 |
| `GET` | `/ui/fragments/workflows` | 분석과 Draft PR 진행을 합친 최근 작업 목록의 최초 로드 | agent DB만 |
| `GET` | `/ui/fragments/workflows/{analysisId}` | 실행 중이거나 방금 완료된 분석 카드 하나만 갱신 | agent DB만 |
| `GET` | `/ui/fragments/analyses/{analysisId}/action` | 분석 버튼의 진행·완료·중복 재분석 상태 갱신 | agent DB만 |
| `GET` | `/ui/fragments/command` | 목록에서 선택한 자연어 입력 채우기 | 없음 |
| `POST` | `/ui/natural-language/interpretations` | 자연어 해석 미리보기 | LiteLLM 해석만 |
| `POST` | `/ui/natural-language/interpretations/{id}/executions` | version/hash 확인 실행 | typed command에 따라 결정 |
| `GET` | `/ui/fragments/analyses/{analysisId}` | 분석 상태와 후보 목록 | agent DB만 |
| `POST` | `/ui/analyses/{analysisId}/selections` | 후보 선택과 hotfix 접수 | 기존 selection workflow |
| `POST` | `/ui/hotfixes/{hotfixId}/restarts` | 동일 후보를 새 hotfix로 안전 게이트부터 재시작 | agent DB, hotfix workflow |
| `POST` | `/ui/hotfixes/{hotfixId}/human-review-branch` | 기존 `agent/hotfix/*` branch를 사람 검토용으로 게시 | Bitbucket, agent DB |
| `POST` | `/ui/hotfixes/{hotfixId}/human-changes-verification` | 사람이 push한 commit을 다시 가져와 전체 검증 후 Draft PR 진행 | Bitbucket, local verification, agent DB |
| `POST` | `/ui/hotfixes/{hotfixId}/ci-refresh` | 생성된 Draft PR의 Jenkins 상태 명시적 갱신 | Jenkins, agent DB |
| `DELETE` | `/ui/hotfixes/{hotfixId}` | 실행 취소 후 해당 원인의 로컬 hotfix 기록 삭제 | agent DB만 |
| `DELETE` | `/ui/workflows/{analysisId}` | 연결된 실행 취소 후 분석과 로컬 hotfix 기록 삭제 | agent DB만 |

## 3. HTMX 갱신 규칙

- 실패 PR fragment는 최초 `load`와 사용자가 누른 새로고침에서만 호출한다.
- 관측 fragment에는 주기 trigger가 없고 입력 form 제출로만 호출한다.
- 통합 workflow 목록은 최초 한 번만 불러오고, 실행 중인 analysis 카드와 분석 요청 버튼만 2초마다
  각각 로컬 상태를 읽는다. 다른 workflow 카드는 교체하거나 숨기지 않는다.
- terminal analysis에는 polling attribute를 제거한다.
- 첫 페이지 로드에서는 PostgreSQL `hotfix_agent` 스키마의 최근 분석과 hotfix 진행 이력을 복원한다.
- 화면 polling은 Jenkins/Grafana/Bitbucket 자동 탐지를 의미하지 않는다.
- `AI 분석 요청` 중에는 해당 PR 버튼 안에서 진행 상태를 표시한다. 완료되면 해당 analysis 카드만
  목록 맨 위에 삽입 또는 갱신하며, 동일 요청이 이미 완료된 경우 버튼을 `중복 요청 재분석`으로 바꾼다.

각 DB 분석은 하나의 작업 카드이며 연결된 hotfix를 `analysisId`로 결합한다. 카드는
`분석 → Draft PR 생성 → Draft PR 로컬 빌드·테스트 → Draft PR Jenkins CI`의 네 단계를
한 줄로 보여주며, 현재 동작 중인 agent와 중단 사유도 같은 카드에 표시한다.

하나의 분석에 여러 후보가 있으면 `candidateId`별로 별도의 hotfix를 결합한다. 따라서 한 원인의 선택,
취소, 재시작, branch, Draft PR과 CI 상태가 다른 원인을 덮어쓰지 않는다. 취소는 실행 registry에 먼저
전달되고 workflow는 외부 PR 생성 전 cancellation을 다시 확인한다.

- 카드 X는 완료된 분석과 연결된 로컬 기록을 삭제한다.
- 실행 중인 patch/로컬 검증의 취소는 해당 hotfix만 삭제하여 다른 후보와 분석 결과를 보존한다.
- 실패 또는 사람 검토 상태의 재시작은 중간 산출물을 신뢰해 건너뛰지 않고 source freshness와 모든
  guardrail을 포함한 hotfix workflow 처음부터 새 ID로 실행한다.
- branch가 생성된 실패 작업은 기존 `agent/hotfix/*` branch를 Bitbucket에 게시할 수 있다. 사람이 같은
  branch에 수정 commit을 push한 뒤 재검증을 요청하면 기준 commit 계보, evidence 파일 범위, 금지 경로,
  10파일/500줄 한도, 집중 테스트, 독립 AI review와 Jenkins parity를 모두 다시 통과해야 Draft PR을 만든다.
- 진행·실패 상태는 현재 세부 단계, 실패 코드, 복구 안내와 redaction·크기 제한된 검증 출력 요약을 DB에
  저장하여 페이지 재로드 후에도 동일하게 표시한다.
- CI 단계는 Jenkins를 임의 재빌드하지 않고 명시적 상태 갱신과 Bitbucket/Jenkins 원본 링크를 제공한다.
- 로컬 삭제는 이미 생성된 Bitbucket Draft PR이나 Jenkins 기록을 삭제하지 않는다.

## 4. 동일 요청과 강제 재분석

- 실패 PR 분석 키는 `PR + Jenkins build + source commit`으로 고정한다.
- 동일 키의 분석이 DB에 있으면 새 분석을 만들지 않고 저장된 상태와 후보를 표시한다.
- 저장된 동일 요청 화면에서만 `강제로 다시 분석` 버튼을 제공한다.
- 강제 재분석은 원래 키에 새로운 UUID suffix를 붙여 기존 이력을 덮어쓰지 않고 별도 row로 저장한다.
- 일반 재시도와 강제 재시도 모두 기존 source revision 검증과 안전 정책을 우회하지 않는다.

## 5. Draft PR 사용자 흐름

```text
자연어 입력
  → 해석 결과와 parameter 확인
  → “이 해석으로 실행”
또는 실패 PR의 “AI 분석 요청”
  → typed Jenkins 분석 직접 접수
  → 분석 완료 및 후보 표시
  → ELIGIBLE 후보의 “이 후보로 Draft PR 생성”
  → patch/검증 진행 표시
  → parity 성공 후 Bitbucket Draft PR 링크 표시
```

후보 선택 이후에도 변경량, 금지 경로, source freshness, review, Gradle/JaCoCo/Jib/Compose/Newman과
Draft-only 정책은 기존 workflow에서 다시 검사한다.

각 비동기 요청은 현재 동작 중인 Embabel agent 이름을 표시한다. HTTP 4xx/5xx 응답은 대상 fragment에
오류 내용을 렌더링하면서 공통 toast도 함께 표시하며, 네트워크 실패와 timeout도 toast로 알린다.
