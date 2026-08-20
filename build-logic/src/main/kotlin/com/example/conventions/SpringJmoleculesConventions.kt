package com.example.conventions

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

class SpringJmoleculesConventions : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

        dependencies {
            add("implementation", platform(libs.findLibrary("jmolecules-bom").get()))
            add("implementation", libs.findLibrary("jmolecules-ddd").get())
            add("implementation", libs.findLibrary("jmolecules-hexagonal-architecture").get())

            add("testImplementation", libs.findLibrary("jmolecules-archunit").get())
            add("testImplementation", libs.findLibrary("tngtech-archunit").get())
        }
    }
}
