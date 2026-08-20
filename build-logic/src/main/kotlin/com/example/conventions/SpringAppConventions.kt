package com.example.conventions

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

class SpringAppConventions : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        plugins.apply("com.example.conventions.java")
        plugins.apply("com.example.conventions.java-library")

        plugins.apply("org.springframework.boot")

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

        dependencies {
            val springBootBom = dependencies.platform(libs.findLibrary("spring-boot-dependencies-bom").get())

            add("implementation", springBootBom)
            add("annotationProcessor", springBootBom)
            add("testImplementation", springBootBom)
            add("testAnnotationProcessor", springBootBom)

            add("implementation", "org.springframework.boot:spring-boot-starter-actuator")
            add("implementation", "org.springframework.boot:spring-boot-starter-opentelemetry")
            add("implementation", "io.micrometer:micrometer-registry-otlp")
            add("implementation", "io.micrometer:micrometer-registry-prometheus")
//            add("developmentOnly", "org.springframework.boot:spring-boot-devtools")
            add("testImplementation", "org.springframework.boot:spring-boot-starter-test")
        }

        tasks.named("bootJar") {
            enabled = false
        }
        tasks.named("jar") {
            enabled = true
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}
