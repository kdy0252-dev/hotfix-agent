package com.example.conventions

import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

class SpringErrorProneConventions : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        plugins.apply("com.example.conventions.java")

        plugins.apply("net.ltgt.errorprone")

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

        dependencies {
            add("errorprone", libs.findLibrary("errorprone-core").get())
        }

        // https://errorprone.info/bugpatterns 에 명시된 On by default: ERROR/WARNING이 자동으로 켜져있음
        tasks.withType<JavaCompile>().configureEach {
            options.isIncremental = false

            options.errorprone.apply {
                disableWarningsInGeneratedCode.set(true) // 개발자가 직접 작성하지 않은 자동 생성 코드 검증 OFF
                excludedPaths.set(".*/generated/.*")     // generated 경로 제외(필요 시 패턴 조정)
                allErrorsAsWarnings.set(false)           // 모든 Warning은 Error로 승격

                disable("JUnit3FloatingPointComparisonWithoutDelta")
                disable("JUnit4ClassUsedInJUnit3")
                disable("ExtendingJUnitAssert")

                options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
            }
        }

    }
}
