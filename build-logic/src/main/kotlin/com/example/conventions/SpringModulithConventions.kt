package com.example.conventions

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

class SpringModulithConventions : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        plugins.apply("com.example.conventions.spring-app")

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

        dependencies {
            add("implementation", dependencies.platform(libs.findLibrary("spring-modulith-bom").get()))
            add("testImplementation", dependencies.platform(libs.findLibrary("spring-modulith-bom").get()))

            add("implementation", "org.springframework.modulith:spring-modulith-starter-core")
            add("implementation", "org.springframework.modulith:spring-modulith-starter-jpa")
            add("runtimeOnly", "org.springframework.modulith:spring-modulith-actuator")
            add("runtimeOnly", "org.springframework.modulith:spring-modulith-observability")
            add("testImplementation", "org.springframework.modulith:spring-modulith-starter-test")
        }
    }
}
