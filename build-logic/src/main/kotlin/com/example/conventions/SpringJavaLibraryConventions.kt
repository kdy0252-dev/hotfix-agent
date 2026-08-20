package com.example.conventions

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class SpringJavaLibraryConventions : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        plugins.apply("com.example.conventions.java")

        configurations.named("compileOnly") {
            extendsFrom(configurations.named("annotationProcessor").get())
        }

        val libs =
            project.extensions.getByType(org.gradle.api.artifacts.VersionCatalogsExtension::class.java)
                .named("libs")

        dependencies {
            add("implementation", libs.findLibrary("slf4j-api").get())
            add("testImplementation", libs.findLibrary("slf4j-api").get())

            add("compileOnly", "org.projectlombok:lombok")
            add("annotationProcessor", "org.projectlombok:lombok")
            add("testCompileOnly", "org.projectlombok:lombok")
            add("testAnnotationProcessor", "org.projectlombok:lombok")

            add("testImplementation", "org.mockito:mockito-core")
        }
    }
}