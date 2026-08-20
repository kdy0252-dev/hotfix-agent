package com.example.conventions

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

class SpringCloudGatewayConventions : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        plugins.apply("com.example.conventions.spring-app")


        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

        dependencies {
            add("implementation", platform(libs.findLibrary("spring-cloud-dependencies-bom").get()))
            // 게이트웨이 엔진 : Servlet 방식으로 동작하는 게이트웨이의 핵심 스타터
            add("implementation", "org.springframework.cloud:spring-cloud-starter-gateway-server-webmvc")
            // Swagger
            add("implementation", libs.findLibrary("spring-docs").get())
        }

    }
}