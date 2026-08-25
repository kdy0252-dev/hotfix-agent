package com.example.myagent.incident.application.domain.model.hotfix;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.JenkinsfileProfile;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.StageResult;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.Verification;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource.VerificationProvenance;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class HotfixResourceSerializationTest {

    @Test
    void serializesTheCommitAndJenkinsParityProvenance() throws Exception {
        var verification = new Verification(
            2,
            new VerificationProvenance(
                "base-commit",
                "patch-commit",
                new JenkinsfileProfile("eu/Jenkinsfile", "sha256", 3)
            ),
            List.of(new StageResult("jenkins-gradle-verification", 0, true, "passed"))
        );

        var json = new ObjectMapper().valueToTree(verification);

        assertThat(json.at("/provenance/baseCommit").asText()).isEqualTo("base-commit");
        assertThat(json.at("/provenance/patchCommit").asText()).isEqualTo("patch-commit");
        assertThat(json.at("/provenance/jenkinsfile/path").asText())
            .isEqualTo("eu/Jenkinsfile");
        assertThat(json.at("/provenance/jenkinsfile/sha256").asText()).isEqualTo("sha256");
        assertThat(json.at("/provenance/jenkinsfile/profileVersion").asInt()).isEqualTo(3);
        assertThat(json.at("/stages/0/exitCode").asInt()).isZero();
    }
}
