package com.example.myagent.incident.adapter.out.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class JenkinsParityProfile {
    static final String JENKINSFILE = "eu/Jenkinsfile";
    static final String GRADLE_STAGE = "jenkins-gradle-verification";
    static final String COVERAGE_STAGE = "jenkins-coverage-report";
    static final String IMAGE_STAGE = "jenkins-image-build";
    static final String INTEGRATION_STAGE = "jenkins-integration-test";

    private static final List<String> GRADLE_TASKS = List.of(
        ":eu:eu-app:architectureTest", ":eu:eu-app:checkstyleMain",
        ":eu:eu-gateway:checkstyleMain",
        ":eu:eu-metrics:architectureTest", ":eu:eu-metrics:checkstyleMain",
        ":eu:eu-app:integrationTest", ":eu:eu-app:migrationTest", ":eu:eu-app:test",
        ":eu:eu-gateway:test", ":eu:eu-metrics:test",
        ":eu:eu-app:jacocoTestReport", ":eu:eu-app:jacocoIntegrationTestReport",
        ":eu:eu-app:jacocoTestAndIntegrationTestReport",
        ":eu:eu-gateway:jacocoTestReport", ":eu:eu-metrics:jacocoTestReport",
        ":eu:eu-app:externalApiTest"
    );

    private JenkinsParityProfile() {
    }

    static List<String> gradleTasks() {
        return GRADLE_TASKS;
    }

    static List<String> gradleCommand() {
        var command = new ArrayList<>(List.of(
            "./gradlew", "--parallel", "--max-workers=6"
        ));
        command.addAll(GRADLE_TASKS);
        return List.copyOf(command);
    }

    static Set<String> requiredStages() {
        return Set.of(GRADLE_STAGE, COVERAGE_STAGE, IMAGE_STAGE, INTEGRATION_STAGE);
    }
}
