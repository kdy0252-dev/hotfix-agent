package com.example.myagent.incident.adapter.out.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class JenkinsParityProfileTest {

    @Test
    void containsEveryDeploymentExcludedJenkinsVerificationStageAndTask() {
        assertThat(JenkinsParityProfile.JENKINSFILE).isEqualTo("eu/Jenkinsfile");
        assertThat(JenkinsParityProfile.requiredStages()).containsExactlyInAnyOrderElementsOf(Set.of(
            "jenkins-gradle-verification",
            "jenkins-coverage-report",
            "jenkins-image-build",
            "jenkins-integration-test"
        ));
        assertThat(JenkinsParityProfile.gradleTasks()).containsExactly(
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
        assertThat(JenkinsParityProfile.gradleCommand())
            .startsWith("./gradlew", "--parallel", "--max-workers=6")
            .endsWith(":eu:eu-app:externalApiTest");
    }
}
