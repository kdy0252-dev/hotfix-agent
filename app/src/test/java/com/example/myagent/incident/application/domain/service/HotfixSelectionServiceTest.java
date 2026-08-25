package com.example.myagent.incident.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.global.configuration.AgentRuntimeProperties;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.analysis.SourceRevision;
import com.example.myagent.incident.application.domain.model.analysis.SourceSpec;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import com.example.myagent.incident.application.domain.service.internal.HotfixExecutionRegistry;
import com.example.myagent.incident.application.port.in.IncidentUseCaseException;
import com.example.myagent.incident.application.port.in.SelectCandidateUseCase.SelectionCommand;
import com.example.myagent.incident.application.port.out.HotfixWorkflowPort;
import com.example.myagent.incident.application.port.out.IncidentStatePort;
import com.example.myagent.incident.application.port.out.SourceRevisionPort;
import io.vavr.control.Either;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;

class HotfixSelectionServiceTest {

    @Test
    void rejectsAnEligibleLabelWithoutSourceAndEvidenceCoverage() {
        var statePort = mock(IncidentStatePort.class);
        var sourceRevisionPort = mock(SourceRevisionPort.class);
        var workflowPort = mock(HotfixWorkflowPort.class);
        var uncoveredCandidate = new BugCandidate(
            new BugCandidate.Identity(
                "candidate-1",
                "Unsupported candidate",
                "No grounded source",
                0.9,
                BugCandidate.Eligibility.ELIGIBLE
            ),
            new BugCandidate.Evidence(List.of(), List.of(), List.of()),
            new BugCandidate.Recommendation("Guess a fix", "Guess a test")
        );
        var analysis = analysis(uncoveredCandidate);
        var service = selectionService(statePort, sourceRevisionPort, workflowPort);
        when(statePort.findHotfixByIdempotencyKey("selection-key"))
            .thenReturn(Either.right(Optional.empty()));
        when(statePort.findAnalysis("analysis-1")).thenReturn(Either.right(Optional.of(
            new IncidentStatePort.AnalysisEnvelope(1, "analysis-key", "hash", analysis)
        )));

        assertThatThrownBy(() -> service.select(new SelectionCommand(
            "analysis-1",
            "candidate-1",
            1,
            "selection-key"
        ))).isInstanceOf(IncidentUseCaseException.class)
            .extracting(exception -> ((IncidentUseCaseException) exception).code())
            .isEqualTo("CANDIDATE_NOT_ELIGIBLE");

        verify(sourceRevisionPort, never()).resolve(any());
        verify(statePort, never()).saveHotfix(any());
        verify(workflowPort, never()).execute(any(), any(), any(), any(), any());
    }

    @Test
    void returnsSelectedStateBeforeRunningPatchAndParityWorkflow() {
        var statePort = mock(IncidentStatePort.class);
        var sourceRevisionPort = mock(SourceRevisionPort.class);
        var workflowPort = mock(HotfixWorkflowPort.class);
        var tasks = new CapturingTaskExecutor();
        var analysis = analysis();
        var candidate = analysis.result().candidates().getFirst();
        var service = new HotfixSelectionService(
            statePort,
            sourceRevisionPort,
            workflowPort,
            new AgentRuntimeProperties(
                AgentRuntimeProperties.Mode.DRAFT_PR,
                Path.of("/tmp/fms"),
                Duration.ofHours(24)
            ),
            Clock.fixed(Instant.parse("2026-08-20T01:00:00Z"), ZoneOffset.UTC),
            tasks,
            new HotfixExecutionRegistry()
        );
        when(statePort.findHotfixByIdempotencyKey("selection-key"))
            .thenReturn(Either.right(Optional.empty()));
        when(statePort.findAnalysis("analysis-1")).thenReturn(Either.right(Optional.of(
            new IncidentStatePort.AnalysisEnvelope(1, "analysis-key", "hash", analysis)
        )));
        when(sourceRevisionPort.resolve(analysis.snapshot().source()))
            .thenReturn(Either.right(analysis.snapshot().sourceRevision()));
        when(statePort.saveHotfix(any())).thenAnswer(invocation -> {
            IncidentStatePort.HotfixEnvelope envelope = invocation.getArgument(0);
            return Either.right(envelope.resource());
        });

        HotfixResource response = service.select(new SelectionCommand(
            "analysis-1",
            candidate.identity().candidateId(),
            1,
            "selection-key",
            HotfixResource.PatchInstruction.from(
                "중복 요청이면 기존 결과를 반환하도록 수정"
            )
        ));

        assertThat(response.progress().status()).isEqualTo(HotfixResource.Status.SELECTED);
        assertThat(response.patchInstruction().text())
            .isEqualTo("중복 요청이면 기존 결과를 반환하도록 수정");
        assertThat(tasks.pending()).isEqualTo(1);
        verify(workflowPort, never()).execute(any(), any(), any(), any(), any());

        HotfixResource completed = completed(response);
        when(workflowPort.execute(any(), any(), any(), any(), any()))
            .thenReturn(Either.right(completed));
        tasks.runNext();

        var envelope = ArgumentCaptor.forClass(IncidentStatePort.HotfixEnvelope.class);
        verify(statePort, times(2)).saveHotfix(envelope.capture());
        assertThat(envelope.getValue().resource()).isEqualTo(completed);
        var workflowHotfix = ArgumentCaptor.forClass(HotfixResource.class);
        verify(workflowPort).execute(any(), any(), workflowHotfix.capture(), any(), any());
        assertThat(workflowHotfix.getValue().patchInstruction().text())
            .isEqualTo("중복 요청이면 기존 결과를 반환하도록 수정");
    }

    @Test
    void appliesTheExtendedRuntimeTtlToAnExistingAnalysisSnapshot() {
        var statePort = mock(IncidentStatePort.class);
        var sourceRevisionPort = mock(SourceRevisionPort.class);
        var workflowPort = mock(HotfixWorkflowPort.class);
        var analysis = analysis();
        var candidate = analysis.result().candidates().getFirst();
        var service = selectionServiceWithTtl(
            statePort,
            sourceRevisionPort,
            workflowPort,
            Duration.ofDays(3),
            Instant.parse("2026-08-21T01:00:00Z")
        );
        when(statePort.findHotfixByIdempotencyKey("selection-key"))
            .thenReturn(Either.right(Optional.empty()));
        when(statePort.findAnalysis("analysis-1")).thenReturn(Either.right(Optional.of(
            new IncidentStatePort.AnalysisEnvelope(1, "analysis-key", "hash", analysis)
        )));
        when(sourceRevisionPort.resolve(analysis.snapshot().source()))
            .thenReturn(Either.right(analysis.snapshot().sourceRevision()));
        when(statePort.saveHotfix(any())).thenAnswer(invocation -> {
            IncidentStatePort.HotfixEnvelope envelope = invocation.getArgument(0);
            return Either.right(envelope.resource());
        });

        HotfixResource selected = service.select(new SelectionCommand(
            "analysis-1",
            candidate.identity().candidateId(),
            1,
            "selection-key"
        ));

        assertThat(selected.progress().status()).isEqualTo(HotfixResource.Status.SELECTED);
    }

    @Test
    void rejectsAnAnalysisAfterTheConfiguredThreeDayTtl() {
        var statePort = mock(IncidentStatePort.class);
        var sourceRevisionPort = mock(SourceRevisionPort.class);
        var workflowPort = mock(HotfixWorkflowPort.class);
        var analysis = analysis();
        var candidate = analysis.result().candidates().getFirst();
        var service = selectionServiceWithTtl(
            statePort,
            sourceRevisionPort,
            workflowPort,
            Duration.ofDays(3),
            Instant.parse("2026-08-23T01:00:00Z")
        );
        when(statePort.findHotfixByIdempotencyKey("selection-key"))
            .thenReturn(Either.right(Optional.empty()));
        when(statePort.findAnalysis("analysis-1")).thenReturn(Either.right(Optional.of(
            new IncidentStatePort.AnalysisEnvelope(1, "analysis-key", "hash", analysis)
        )));

        assertThatThrownBy(() -> service.select(new SelectionCommand(
            "analysis-1",
            candidate.identity().candidateId(),
            1,
            "selection-key"
        ))).isInstanceOf(IncidentUseCaseException.class)
            .extracting(exception -> ((IncidentUseCaseException) exception).code())
            .isEqualTo("ANALYSIS_EXPIRED");

        verify(sourceRevisionPort, never()).resolve(any());
    }

    private AnalysisSession analysis() {
        var candidate = new BugCandidate(
            new BugCandidate.Identity(
                "candidate-1",
                "Null booking response",
                "BookingService dereferences null",
                0.95,
                BugCandidate.Eligibility.ELIGIBLE
            ),
            new BugCandidate.Evidence(
                List.of("eu/eu-app/src/main/java/BookingService.java:84"),
                List.of("jenkins:181"),
                List.of()
            ),
            new BugCandidate.Recommendation("Guard response", "Run parity")
        );
        return analysis(candidate);
    }

    private AnalysisSession analysis(BugCandidate candidate) {
        return new AnalysisSession(
            new AnalysisSession.Identity("analysis-1", 1, "hash"),
            new AnalysisSession.Snapshot(
                SourceSpec.branch("main"),
                new SourceRevision("base123", "main", "bitbucket:branch:main"),
                Instant.parse("2026-08-20T00:00:00Z"),
                Instant.parse("2026-08-21T00:00:00Z")
            ),
            new AnalysisSession.Result(
                AnalysisSession.Status.CANDIDATES_READY,
                List.of(candidate),
                null
            )
        );
    }

    private HotfixSelectionService selectionService(
        IncidentStatePort statePort,
        SourceRevisionPort sourceRevisionPort,
        HotfixWorkflowPort workflowPort
    ) {
        return selectionServiceWithTtl(
            statePort,
            sourceRevisionPort,
            workflowPort,
            Duration.ofHours(24),
            Instant.parse("2026-08-20T01:00:00Z")
        );
    }

    private HotfixSelectionService selectionServiceWithTtl(
        IncidentStatePort statePort,
        SourceRevisionPort sourceRevisionPort,
        HotfixWorkflowPort workflowPort,
        Duration analysisTtl,
        Instant now
    ) {
        return new HotfixSelectionService(
            statePort,
            sourceRevisionPort,
            workflowPort,
            new AgentRuntimeProperties(
                AgentRuntimeProperties.Mode.DRAFT_PR,
                Path.of("/tmp/fms"),
                analysisTtl
            ),
            Clock.fixed(now, ZoneOffset.UTC),
            new CapturingTaskExecutor(),
            new HotfixExecutionRegistry()
        );
    }

    private HotfixResource completed(HotfixResource selected) {
        var progress = new HotfixResource.Progress(
            new HotfixResource.WorkflowState(
                HotfixResource.Status.NEEDS_HUMAN_REVIEW,
                "agent/hotfix/example",
                null,
                new HotfixResource.FailureDetail(
                    HotfixResource.WorkflowStage.CODE_REVIEW,
                    "FIXTURE_REVIEW",
                    "fixture completed"
                )
            ),
            HotfixResource.ChangeMetrics.empty(),
            selected.progress().verification()
        );
        return new HotfixResource(selected.identity(), progress, selected.publication());
    }

    private static final class CapturingTaskExecutor implements TaskExecutor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        int pending() {
            return tasks.size();
        }

        void runNext() {
            tasks.remove().run();
        }
    }
}
