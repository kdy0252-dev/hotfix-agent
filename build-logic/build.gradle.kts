plugins {
    `kotlin-dsl`

    kotlin("jvm") version libs.versions.kotlin
    kotlin("plugin.spring") version libs.versions.kotlin

    alias(libs.plugins.errorprone)
}
repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation(libs.spring.boot.gradle.plugin)
    // OpenRewrite
    implementation(libs.rewrite.plugin)
    // ErrorProne
    implementation(libs.errorprone.plugin)
}

gradlePlugin {
    plugins {
        create("springJava") {
            id = "com.example.conventions.java"
            implementationClass = "com.example.conventions.SpringJavaConventions"
        }
        create("springJavaLibrary") {
            id = "com.example.conventions.java-library"
            implementationClass = "com.example.conventions.SpringJavaLibraryConventions"
        }
        create("springSpringApp") {
            id = "com.example.conventions.spring-app"
            implementationClass = "com.example.conventions.SpringAppConventions"
        }
        create("springSpringModulith") {
            id = "com.example.conventions.spring-modulith"
            implementationClass = "com.example.conventions.SpringModulithConventions"
        }
        create("springSpringWeb") {
            id = "com.example.conventions.spring-web"
            implementationClass = "com.example.conventions.SpringWebConventions"
        }
        create("springSpringDocs") {
            id = "com.example.conventions.spring-docs"
            implementationClass = "com.example.conventions.SpringDocsConventions"
        }
        create("springSpringQuartz") {
            id = "com.example.conventions.spring-quartz"
            implementationClass = "com.example.conventions.SpringQuartzConventions"
        }
        create("springSpringThymeleaf") {
            id = "com.example.conventions.spring-thymeleaf"
            implementationClass = "com.example.conventions.SpringThymeleafConventions"
        }
        create("springSpringSecurity") {
            id = "com.example.conventions.spring-security"
            implementationClass = "com.example.conventions.SpringSecurityConventions"
        }
        create("springSpringThymeleafSecurity") {
            id = "com.example.conventions.spring-thymeleaf-security"
            implementationClass = "com.example.conventions.SpringThymeleafSecurityConventions"
        }
        create("springSpringData") {
            id = "com.example.conventions.spring-data"
            implementationClass = "com.example.conventions.SpringDataConventions"
        }
        create("springSpringRedis") {
            id = "com.example.conventions.spring-redis"
            implementationClass = "com.example.conventions.SpringRedisConventions"
        }
        create("SpringCloudGateway") {
            id = "com.example.conventions.spring-cloud-gateway"
            implementationClass = "com.example.conventions.SpringCloudGatewayConventions"
        }
        create("SpringOpenRewriteConventions") {
            id = "com.example.conventions.openrewrite"
            implementationClass = "com.example.conventions.SpringOpenRewriteConventions"
        }
        create("SpringErrorProneConventions") {
            id = "com.example.conventions.errorprone"
            implementationClass = "com.example.conventions.SpringErrorProneConventions"
        }
        create("SpringCheckstyleConventions") {
            id = "com.example.conventions.checkstyle"
            implementationClass = "com.example.conventions.SpringCheckstyleConventions"
        }
        create("SpringTestContainerConventions") {
            id = "com.example.conventions.testcontainer"
            implementationClass = "com.example.conventions.SpringTestContainerConventions"
        }
        create("SpringJmoleculesConventions") {
            id = "com.example.conventions.jmolecules"
            implementationClass = "com.example.conventions.SpringJmoleculesConventions"
        }
    }
}
