import org.gradle.api.tasks.Exec

tasks.register("buildAll") {
    group = "build"
    description = "Builds every application module."
    dependsOn(":app:build")
}

val langfuseComposeFile = layout.projectDirectory.file("infra/langfuse/compose.yml")
val langfuseEnvironmentFile = layout.projectDirectory.file(".agent/runtime/langfuse.env")
val dockerExecutable = providers.environmentVariable("DOCKER_EXECUTABLE")
    .orElse("/usr/local/bin/docker")

val prepareLangfuseEnvironment = tasks.register<Exec>("prepareLangfuseEnvironment") {
    group = "verification"
    description = "Creates ephemeral credentials for the local Langfuse test stack."
    commandLine(
        "zsh",
        layout.projectDirectory.file("scripts/ai-test/prepare-langfuse-env.zsh").asFile,
        langfuseEnvironmentFile.asFile,
    )
    outputs.file(langfuseEnvironmentFile)
}

val langfuseUp = tasks.register<Exec>("langfuseUp") {
    group = "verification"
    description = "Starts the local Langfuse v4 stack used by AI evaluations."
    environment("COMPOSE_IGNORE_ORPHANS", "true")
    dependsOn(prepareLangfuseEnvironment)
    mustRunAfter(":app:compileAiEvaluationTestJava", ":app:aiMockTest")
    commandLine(
        dockerExecutable.get(), "compose",
        "--project-name", "my-agent-ai-test",
        "--env-file", langfuseEnvironmentFile.asFile,
        "--file", langfuseComposeFile.asFile,
        "up", "--detach", "--wait", "--wait-timeout", "240",
    )
}

val langfuseReady = tasks.register<Exec>("langfuseReady") {
    group = "verification"
    description = "Waits until the local Langfuse API is ready."
    dependsOn(langfuseUp)
    commandLine(
        "zsh",
        layout.projectDirectory.file("scripts/ai-test/wait-for-langfuse.zsh").asFile,
        "http://127.0.0.1:13000",
        langfuseEnvironmentFile.asFile,
    )
}

tasks.register<Exec>("langfuseDown") {
    group = "verification"
    description = "Stops the local Langfuse test stack and removes its volumes."
    commandLine(
        dockerExecutable.get(), "compose",
        "--project-name", "my-agent-ai-test",
        "--env-file", langfuseEnvironmentFile.asFile,
        "--file", langfuseComposeFile.asFile,
        "down", "--volumes", "--remove-orphans",
    )
    dependsOn(prepareLangfuseEnvironment)
}

tasks.register<Exec>("langfuseStop") {
    group = "application"
    description = "Stops the app and local Langfuse stack without deleting evaluation data."
    commandLine(
        dockerExecutable.get(), "compose",
        "--project-name", "my-agent-ai-test",
        "--env-file", langfuseEnvironmentFile.asFile,
        "--file", layout.projectDirectory.file("compose.yml").asFile,
        "--file", langfuseComposeFile.asFile,
        "--file", layout.projectDirectory.file("infra/langfuse/app.compose.yml").asFile,
        "stop",
    )
    dependsOn(prepareLangfuseEnvironment)
}

tasks.register<Exec>("runWithLangfuse") {
    group = "application"
    description = "Runs the app container with local Langfuse tracing and live LLM-as-a-judge evaluation."
    dependsOn(langfuseReady)
    commandLine(
        "zsh",
        layout.projectDirectory.file("scripts/run-with-langfuse.zsh").asFile,
        langfuseEnvironmentFile.asFile,
    )
}

tasks.register("aiTest") {
    group = "verification"
    description = "Runs Embabel AI mocks and LiteLLM judge evaluations with local Langfuse."
    dependsOn(":app:aiTest")
}
