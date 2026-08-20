package com.example.conventions

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

class SpringTestContainerConventions : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

        dependencies {
            add("testImplementation", libs.findLibrary("spring-boot-testcontainers").get())
            add("testImplementation", libs.findLibrary("testcontainers-junit-jupiter").get())
            add("testImplementation", libs.findLibrary("testcontainers-postgresql").get())
        }
    }
}
