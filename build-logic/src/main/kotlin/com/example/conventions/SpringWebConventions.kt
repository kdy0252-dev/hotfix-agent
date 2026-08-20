package com.example.conventions

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class SpringWebConventions : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        plugins.apply("com.example.conventions.spring-app")

        dependencies {
            add("implementation", "org.springframework.boot:spring-boot-starter-webmvc")
            add("implementation", "org.springframework.boot:spring-boot-starter-validation")
        }
    }
}
