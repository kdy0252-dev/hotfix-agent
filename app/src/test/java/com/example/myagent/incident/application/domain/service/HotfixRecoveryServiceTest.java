package com.example.myagent.incident.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.analysis.SourceRevision;
import com.example.myagent.incident.application.domain.model.analysis.SourceSpec;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import com.example.myagent.incident.application.domain.service.internal.HotfixExecutionRegistry;
import com.example.myagent.incident.application.domain.service.internal.HotfixRecoveryExecutor;
import com.example.myagent.incident.application.port.out.HotfixWorkflowPort;
import com.example.myagent.incident.application.port.out.IncidentStatePort;
import io.vavr.control.Either;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;

class HotfixRecoveryServiceTest {

    @Test
    void resubmitsAnInterruptedLocalStageOnlyOnce() {
        var statePort = mock(IncidentStatePort.class);
        var workflowPort = mock(HotfixWorkflowPort.class);
        var tasks = new CapturingTaskExecutor();
        var executor = new HotfixRecoveryExecutor(
            statePort,
            workflowPort,
            new HotfixExecutionRegistry(),
            tasks
        );
        var service = new HotfixRecoveryService(statePort, executor);
        var analysis = analysis();
        var interrupted = envelope(hotfix(HotfixResource.Status.VERIFYING));
        when(statePort.findAllHotfixes()).thenReturn(Either.right(List.of(interrupted)));
        when(statePort.findAnalysis("analysis-1")).thenReturn(Either.right(Optional.of(
            new IncidentStatePort.AnalysisEnvelope(1, "analysis-key", "hash", analysis)
        )));
        when(statePort.saveHotfix(any())).thenAnswer(invocation -> Either.right(
            invocation.<IncidentStatePort.HotfixEnvelope>getArgument(0).resource()
        ));
        when(workflowPort.execute(any(), any(), any(), any(), any()))
            .thenReturn(Either.right(hotfix(HotfixResource.Status.DRAFT_PR_CREATED)));

        int recovered = service.recoverInterruptedHotfixes();

        assertThat(recovered).isEqualTo(1);
        assertThat(tasks.pending()).isEqualTo(1);
        verify(workflowPort, never()).execute(any(), any(), any(), any(), any());
        var saved = ArgumentCaptor.forClass(IncidentStatePort.HotfixEnvelope.class);
        verify(statePort).saveHotfix(saved.capture());
        assertThat(saved.getValue().resource().progress().activity().message())
            .contains("서버 재기동");

        tasks.runNext();

        verify(workflowPort).execute(any(), any(), any(), any(), any());
    }

    @Test
    void leavesJenkinsCiAttachedWithoutStartingAnotherLocalWorkflow() {
        var statePort = mock(IncidentStatePort.class);
        var workflowPort = mock(HotfixWorkflowPort.class);
        var tasks = new CapturingTaskExecutor();
        var executor = new HotfixRecoveryExecutor(
            statePort,
            workflowPort,
            new HotfixExecutionRegistry(),
            tasks
        );
        var service = new HotfixRecoveryService(statePort, executor);
        when(statePort.findAllHotfixes()).thenReturn(Either.right(List.of(
            envelope(hotfix(HotfixResource.Status.DRAFT_PR_CREATED))
        )));

        int recovered = service.recoverInterruptedHotfixes();

        assertThat(recovered).isZero();
        assertThat(tasks.pending()).isZero();
        verify(workflowPort, never()).execute(any(), any(), any(), any(), any());
    }

    private IncidentStatePort.HotfixEnvelope envelope(HotfixResource resource) {
        return new IncidentStatePort.HotfixEnvelope(1, "hotfix-key", "hash", resource);
    }

    private HotfixResource hotfix(HotfixResource.Status status) {
        var stage = status == HotfixResource.Status.DRAFT_PR_CREATED
            ? HotfixResource.WorkflowStage.CI : HotfixResource.WorkflowStage.FOCUSED_VERIFICATION;
        var publication = status == HotfixResource.Status.DRAFT_PR_CREATED
            ? new HotfixResource.Publication(
                null,
                "https://bitbucket.example/pull-requests/99",
                "https://jenkins.example/job/PR-99/",
                "PENDING"
            )
            : HotfixResource.Publication.empty();
        return new HotfixResource(
            new HotfixResource.Identity("hotfix-1", "analysis-1", "candidate-1"),
            new HotfixResource.Progress(
                new HotfixResource.WorkflowState(
                    status,
                    "agent/hotfix/example",
                    new HotfixResource.ExecutionDetail(stage, "진행 중"),
                    null
                ),
                HotfixResource.ChangeMetrics.empty(),
                HotfixResource.Verification.empty()
            ),
            publication
        );
    }

    private AnalysisSession analysis() {
        var candidate = new BugCandidate(
            new BugCandidate.Identity(
                "candidate-1",
                "Compile failure",
                "Missing type",
                0.9,
                BugCandidate.Eligibility.ELIGIBLE
            ),
            new BugCandidate.Evidence(
                List.of("eu/src/Foo.java:1"),
                List.of("jenkins:1"),
                List.of()
            ),
            new BugCandidate.Recommendation("Restore type", "Run tests")
        );
        return new AnalysisSession(
            new AnalysisSession.Identity("analysis-1", 1, "hash"),
            new AnalysisSession.Snapshot(
                SourceSpec.pullRequest(1292),
                new SourceRevision("commit-1", "main", "bitbucket:pr:1292"),
                Instant.parse("2026-08-24T00:00:00Z"),
                Instant.parse("2026-08-25T00:00:00Z")
            ),
            new AnalysisSession.Result(
                AnalysisSession.Status.CANDIDATES_READY,
                List.of(candidate),
                null
            )
        );
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
