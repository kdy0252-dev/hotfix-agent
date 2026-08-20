import org.gradle.api.tasks.Exec

tasks.register("buildAll") {
    group = "build"
    description = "Builds every application module."
    dependsOn(":app:build")
}

val langfuseComposeFile = layout.projectDirectory.file("infra/langfuse/compose.yml")
val langfuseEnvironmentFile = layout.buildDirectory.file("ai-test/langfuse.env")

val prepareLangfuseEnvironment = tasks.register<Exec>("prepareLangfuseEnvironment") {
    group = "verification"
    description = "Creates ephemeral credentials for the local Langfuse test stack."
    commandLine(
        "zsh",
        layout.projectDirectory.file("scripts/ai-test/prepare-langfuse-env.zsh").asFile,
        langfuseEnvironmentFile.get().asFile,
    )
    outputs.file(langfuseEnvironmentFile)
}

val langfuseUp = tasks.register<Exec>("langfuseUp") {
    group = "verification"
    description = "Starts the local Langfuse v4 stack used by AI evaluations."
    dependsOn(prepareLangfuseEnvironment)
    mustRunAfter(":app:compileAiEvaluationTestJava", ":app:aiMockTest")
    commandLine(
        "docker", "compose",
        "--project-name", "my-agent-ai-test",
        "--env-file", langfuseEnvironmentFile.get().asFile,
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
        langfuseEnvironmentFile.get().asFile,
    )
}

tasks.register<Exec>("langfuseDown") {
    group = "verification"
    description = "Stops the local Langfuse test stack and removes its volumes."
    commandLine(
        "docker", "compose",
        "--project-name", "my-agent-ai-test",
        "--env-file", langfuseEnvironmentFile.get().asFile,
        "--file", langfuseComposeFile.asFile,
        "down", "--volumes", "--remove-orphans",
    )
    dependsOn(prepareLangfuseEnvironment)
}

tasks.register("aiTest") {
    group = "verification"
    description = "Runs Embabel AI mocks and LiteLLM judge evaluations with local Langfuse."
    dependsOn(":app:aiTest")
}
