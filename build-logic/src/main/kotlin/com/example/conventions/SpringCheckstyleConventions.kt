package com.example.conventions

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

class SpringCheckstyleConventions : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        plugins.apply("com.example.conventions.java")

        plugins.apply("checkstyle")

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

        extensions.configure<CheckstyleExtension> {
            toolVersion = libs.findVersion("checkstyle").get().requiredVersion
            isShowViolations = true
            configFile = file("${rootDir}/build-logic/src/main/resources/checkstyle/checkstyle.xml")
        }

        tasks.withType<Checkstyle>().configureEach {
            // 위반 1건도 허용 안 함
            maxWarnings = 0
            isIgnoreFailures = false

            // 생성코드/빌드 산출물 제외
            exclude("**/generated/**", "**/build/**")

            reports {
                xml.required.set(true)
                html.required.set(true)
            }
        }

        // checkstyleTest 끄기 (있을 때만 안전하게 끔)
        tasks.withType(Checkstyle::class)
            .matching { it.name == "checkstyleTest" }
            .configureEach { enabled = false }
        // build 실행시 check 먼저 실행
        tasks.matching { it.name == "build" }.configureEach {
            dependsOn("check")
        }
        tasks.matching { it.name == "check" }.configureEach {
            dependsOn("checkstyleMain", "checkstyleTest")
        }
    }
}
