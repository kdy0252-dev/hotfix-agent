# 공통 빌드 규칙

생성되는 각 프로젝트가 동일한 Java, Spring 및 코드 품질 설정을 사용하도록
Gradle convention plugin을 제공한다.

## 구조

```text
build-logic/
├── build.gradle.kts
├── settings.gradle.kts
└── src/main/
    ├── kotlin/com/example/conventions/ # convention plugin 구현
    └── resources/checkstyle/           # 공통 Checkstyle 규칙
```

## 주요 플러그인

| 목적 | 플러그인 |
|---|---|
| Java | `com.example.conventions.java`, `com.example.conventions.java-library` |
| Spring | `com.example.conventions.spring-app`, `spring-web`, `spring-docs`, `spring-data` |
| 품질 | `checkstyle`, `errorprone`, `openrewrite`, `jmolecules` |
| 테스트 | `testcontainer`, Spring Modulith test |

프로젝트 루트의 `./gradlew :app:build`를 실행하면 컴파일, 테스트,
Checkstyle과 아키텍처 검증이 함께 수행된다.
