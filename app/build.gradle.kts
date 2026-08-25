import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    id("com.example.conventions.spring-web")
    id("com.example.conventions.spring-thymeleaf")
    id("com.example.conventions.spring-data")
    id("com.example.conventions.spring-docs")
    id("com.example.conventions.openrewrite")
    id("com.example.conventions.errorprone")
    id("com.example.conventions.checkstyle")
    id("com.example.conventions.testcontainer")
    id("com.example.conventions.jmolecules")
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

dependencies {
    implementation(platform(libs.spring.modulith.bom))
    testImplementation(platform(libs.spring.modulith.bom))
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    runtimeOnly("org.springframework.modulith:spring-modulith-actuator")
    runtimeOnly("org.springframework.modulith:spring-modulith-observability")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    implementation(libs.embabel.agent.starter.openai)
    implementation(libs.vavr)
    implementation(libs.htmx)
    implementation(libs.flatpickr)
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("jakarta.persistence:jakarta.persistence-api")
    testImplementation("org.springframework.data:spring-data-commons")
}

val aiMockTest = sourceSets.create("aiMockTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}

val aiEvaluationTest = sourceSets.create("aiEvaluationTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}

configurations[aiMockTest.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[aiMockTest.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())
configurations[aiEvaluationTest.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[aiEvaluationTest.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    add(aiMockTest.implementationConfigurationName, libs.embabel.agent.test)
}

val aiMockTestTask = tasks.register<Test>("aiMockTest") {
    group = "verification"
    description = "Runs offline AI tests backed by Embabel FakeOperationContext."
    testClassesDirs = aiMockTest.output.classesDirs
    classpath = aiMockTest.runtimeClasspath
    useJUnitPlatform()
}

val aiEvaluationTestTask = tasks.register<Test>("aiEvaluationTest") {
    group = "verification"
    description = "Runs LiteLLM-as-a-judge evaluations and publishes scores to Langfuse."
    dependsOn(rootProject.tasks.named("langfuseReady"))
    mustRunAfter(aiMockTestTask)
    finalizedBy(rootProject.tasks.named("langfuseDown"))
    testClassesDirs = aiEvaluationTest.output.classesDirs
    classpath = aiEvaluationTest.runtimeClasspath
    systemProperty("ai.test.project-root", rootProject.projectDir.absolutePath)
    systemProperty(
        "ai.test.langfuse-env",
        rootProject.layout.projectDirectory.file(".agent/runtime/langfuse.env").asFile.absolutePath,
    )
    useJUnitPlatform()
}

tasks.register("aiTest") {
    group = "verification"
    description = "Runs all offline mocks and external AI evaluation tests."
    dependsOn(aiMockTestTask, aiEvaluationTestTask)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-processing")
}

val architectureTestPatterns = listOf("*ArchTest", "*ArchitectureTest")

tasks.named<Test>("test") {
    filter {
        architectureTestPatterns.forEach(::excludeTestsMatching)
    }
}

val architectureTest = tasks.register<Test>("architectureTest") {
    description = "Runs architecture rule tests excluded from the default test task."
    group = "verification"
    maxParallelForks = 1
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    shouldRunAfter(tasks.named("test"))
    filter {
        architectureTestPatterns.forEach(::includeTestsMatching)
    }
    useJUnitPlatform()
}

tasks.named("check") {
    dependsOn(architectureTest)
}

tasks.withType<BootJar>() {
    enabled = true
    entryCompression = ZipEntryCompression.STORED
}

tasks.getByName("jar") {
    enabled = false
}
