package com.example.myagent.incident.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import com.example.myagent.incident.application.domain.service.internal.HotfixExecutionRegistry;
import com.example.myagent.incident.application.port.in.ManageHotfixUseCase;
import com.example.myagent.incident.application.port.in.SelectCandidateUseCase;
import com.example.myagent.incident.application.port.out.IncidentStatePort;
import com.example.myagent.incident.application.port.out.HotfixWorkflowPort;
import io.vavr.control.Either;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;

class HotfixManagementServiceTest {
    @Test
    void restartsTheSameCandidateAsANewGuardedHotfix() {
        var statePort = mock(IncidentStatePort.class);
        var selectionUseCase = mock(SelectCandidateUseCase.class);
        var registry = mock(HotfixExecutionRegistry.class);
        var service = service(statePort, selectionUseCase, registry);
        var previous = hotfix("hotfix-1", "analysis-1", "candidate-1");
        var restarted = hotfix("hotfix-2", "analysis-1", "candidate-1");
        var analysis = mock(AnalysisSession.class);
        var identity = new AnalysisSession.Identity("analysis-1", 3, "hash");
        when(analysis.identity()).thenReturn(identity);
        when(statePort.findHotfix("hotfix-1")).thenReturn(Either.right(Optional.of(
            new IncidentStatePort.HotfixEnvelope(1, "old-key", "old-hash", previous)
        )));
        when(statePort.findAnalysis("analysis-1")).thenReturn(Either.right(Optional.of(
            new IncidentStatePort.AnalysisEnvelope(1, "analysis-key", "hash", analysis)
        )));
        when(selectionUseCase.select(any())).thenReturn(restarted);

        var result = service.restart(new ManageHotfixUseCase.RestartCommand(
            "hotfix-1",
            "restart-key"
        ));

        assertThat(result).isEqualTo(restarted);
        var command = ArgumentCaptor.forClass(SelectCandidateUseCase.SelectionCommand.class);
        verify(selectionUseCase).select(command.capture());
        assertThat(command.getValue().analysisId()).isEqualTo("analysis-1");
        assertThat(command.getValue().candidateId()).isEqualTo("candidate-1");
        assertThat(command.getValue().analysisVersion()).isEqualTo(3);
    }

    @Test
    void cancelsActiveHotfixesBeforeDeletingTheWorkflow() {
        var statePort = mock(IncidentStatePort.class);
        var registry = mock(HotfixExecutionRegistry.class);
        var service = service(statePort, mock(SelectCandidateUseCase.class), registry);
        var first = envelope(hotfix("hotfix-1", "analysis-1", "candidate-1"));
        var second = envelope(hotfix("hotfix-2", "analysis-1", "candidate-2"));
        when(statePort.findAllHotfixes()).thenReturn(Either.right(List.of(first, second)));
        when(statePort.deleteWorkflow("analysis-1")).thenReturn(Either.right(true));

        service.cancelAndDeleteWorkflow("analysis-1");

        verify(registry).cancel("hotfix-1");
        verify(registry).cancel("hotfix-2");
        verify(statePort).deleteWorkflow("analysis-1");
    }

    private IncidentStatePort.HotfixEnvelope envelope(HotfixResource resource) {
        return new IncidentStatePort.HotfixEnvelope(1, "key", "hash", resource);
    }

    private HotfixManagementService service(
        IncidentStatePort statePort,
        SelectCandidateUseCase selectionUseCase,
        HotfixExecutionRegistry registry
    ) {
        return new HotfixManagementService(
            statePort,
            selectionUseCase,
            registry,
            mock(HotfixWorkflowPort.class),
            mock(TaskExecutor.class)
        );
    }

    private HotfixResource hotfix(String hotfixId, String analysisId, String candidateId) {
        return new HotfixResource(
            new HotfixResource.Identity(hotfixId, analysisId, candidateId),
            new HotfixResource.Progress(
                new HotfixResource.WorkflowState(
                    HotfixResource.Status.NEEDS_HUMAN_REVIEW,
                    "agent/hotfix/example",
                    null,
                    new HotfixResource.FailureDetail(
                        HotfixResource.WorkflowStage.CODE_REVIEW,
                        "REVIEW_REQUIRED",
                        "review"
                    )
                ),
                HotfixResource.ChangeMetrics.empty(),
                HotfixResource.Verification.empty()
            ),
            HotfixResource.Publication.empty()
        );
    }
}
