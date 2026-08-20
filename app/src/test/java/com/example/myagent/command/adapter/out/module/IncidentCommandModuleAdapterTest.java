package com.example.myagent.command.adapter.out.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.command.application.domain.model.command.CommandIntent;
import com.example.myagent.command.application.domain.model.command.CommandParameters;
import com.example.myagent.command.application.domain.model.command.InterpretedCommand;
import com.example.myagent.command.application.domain.model.command.SourceReference;
import com.example.myagent.orchestrator.IncidentCommandGateway;
import io.vavr.control.Either;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class IncidentCommandModuleAdapterTest {

    @Test
    void delegatesEveryConfirmedIntentToTheExistingTypedGateway() {
        var gateway = mock(IncidentCommandGateway.class);
        var adapter = new IncidentCommandModuleAdapter(gateway);
        var result = new IncidentCommandGateway.ResourceResult(
            "resource-1", "ACCEPTED", "/resource-1", List.of()
        );
        var jenkins = new IncidentCommandGateway.JenkinsCommand(
            "FMS-EU/job/main",
            181,
            new IncidentCommandGateway.Source("PULL_REQUEST", null, 1285L),
            "key"
        );
        var observability = new IncidentCommandGateway.ObservabilityCommand(
            OffsetDateTime.ofInstant(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC),
            OffsetDateTime.ofInstant(Instant.parse("2026-08-20T00:20:00Z"), ZoneOffset.UTC),
            "PROD",
            new IncidentCommandGateway.Source("BRANCH", "main", null),
            "key"
        );
        var selection = new IncidentCommandGateway.SelectionCommand(
            "analysis-1", 3, "candidate-1", "key"
        );
        when(gateway.analyzeJenkins(jenkins)).thenReturn(result);
        when(gateway.analyzeObservability(observability)).thenReturn(result);
        when(gateway.listCandidates("analysis-1")).thenReturn(result);
        when(gateway.selectCandidate(selection)).thenReturn(result);
        when(gateway.getHotfix("hotfix-1")).thenReturn(result);
        when(gateway.refreshCiStatus("hotfix-1")).thenReturn(result);

        assertAccepted(adapter.dispatch(new InterpretedCommand(
            CommandIntent.ANALYZE_JENKINS,
            new CommandParameters.JenkinsAnalysis(
                "FMS-EU/job/main", 181, new SourceReference.PullRequest(1285)
            )
        ), "key"));
        assertAccepted(adapter.dispatch(new InterpretedCommand(
            CommandIntent.ANALYZE_OBSERVABILITY,
            new CommandParameters.ObservabilityAnalysis(
                Instant.parse("2026-08-20T00:00:00Z"),
                Instant.parse("2026-08-20T00:20:00Z"),
                "prod",
                new SourceReference.Branch("main")
            )
        ), "key"));
        assertAccepted(adapter.dispatch(new InterpretedCommand(
            CommandIntent.LIST_CANDIDATES,
            new CommandParameters.CandidateList("analysis-1")
        ), "key"));
        assertAccepted(adapter.dispatch(new InterpretedCommand(
            CommandIntent.SELECT_CANDIDATE,
            new CommandParameters.CandidateSelection("analysis-1", 3, "candidate-1")
        ), "key"));
        assertAccepted(adapter.dispatch(new InterpretedCommand(
            CommandIntent.GET_HOTFIX_STATUS,
            new CommandParameters.HotfixStatus("hotfix-1")
        ), "key"));
        assertAccepted(adapter.dispatch(new InterpretedCommand(
            CommandIntent.REFRESH_CI_STATUS,
            new CommandParameters.CiStatusRefresh("hotfix-1")
        ), "key"));

        verify(gateway).analyzeJenkins(jenkins);
        verify(gateway).analyzeObservability(observability);
        verify(gateway).listCandidates("analysis-1");
        verify(gateway).selectCandidate(selection);
        verify(gateway).getHotfix("hotfix-1");
        verify(gateway).refreshCiStatus("hotfix-1");
    }

    private void assertAccepted(Either<?, ?> result) {
        assertThat(result.isRight()).isTrue();
    }
}
