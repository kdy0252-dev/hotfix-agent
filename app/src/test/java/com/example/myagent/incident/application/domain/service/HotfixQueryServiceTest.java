package com.example.myagent.incident.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import com.example.myagent.incident.application.port.out.IncidentStatePort;
import com.example.myagent.incident.application.port.out.JenkinsEvidencePort;
import io.vavr.control.Either;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HotfixQueryServiceTest {

    @Test
    void readsJenkinsOnlyOnExplicitRefreshAndResolvesOnlyOnSuccess() {
        var statePort = mock(IncidentStatePort.class);
        var jenkinsPort = mock(JenkinsEvidencePort.class);
        var resource = draftResource();
        var envelope = new IncidentStatePort.HotfixEnvelope(
            1, "selection-key", "request-hash", resource
        );
        when(statePort.findHotfix("hotfix-1"))
            .thenReturn(Either.right(Optional.of(envelope)));
        when(jenkinsPort.refreshPullRequestBuild(resource.publication().ciBuildUrl()))
            .thenReturn(Either.right(new JenkinsEvidencePort.CiBuildSnapshot(
                "https://jenkins.example/job/PR-99/1/",
                new HotfixResource.CiPipeline("SUCCESS", List.of(
                    new HotfixResource.CiStage(
                        "10",
                        "Test",
                        "SUCCESS",
                        new HotfixResource.CiTiming(1_787_527_557_221L, 47_193L),
                        null
                    )
                ))
            )));
        when(statePort.saveHotfix(any()))
            .thenAnswer(invocation -> Either.right(
                invocation.<IncidentStatePort.HotfixEnvelope>getArgument(0).resource()
            ));
        var service = new HotfixQueryService(statePort, jenkinsPort);

        assertThat(service.getHotfix("hotfix-1")).isEqualTo(resource);
        verify(jenkinsPort, never()).refreshPullRequestBuild(resource.publication().ciBuildUrl());

        var refreshed = service.refreshCiStatus("hotfix-1");

        assertThat(refreshed.progress().status()).isEqualTo(HotfixResource.Status.RESOLVED);
        assertThat(refreshed.publication().ciResult()).isEqualTo("SUCCESS");
        assertThat(refreshed.publication().ciStages())
            .extracting(HotfixResource.CiStage::name)
            .containsExactly("Test");
        verify(jenkinsPort).refreshPullRequestBuild(resource.publication().ciBuildUrl());
    }

    private HotfixResource draftResource() {
        return new HotfixResource(
            new HotfixResource.Identity("hotfix-1", "analysis-1", "candidate-1"),
            new HotfixResource.Progress(
                new HotfixResource.WorkflowState(
                    HotfixResource.Status.DRAFT_PR_CREATED,
                    "agent/hotfix/example",
                    null,
                    null
                ),
                new HotfixResource.ChangeMetrics(1, 2),
                HotfixResource.Verification.empty()
            ),
            new HotfixResource.Publication(
                null,
                "https://bitbucket.example/pr/99",
                "https://jenkins.example/job/FMS-EU/job/PR-99/",
                "PENDING"
            )
        );
    }
}
