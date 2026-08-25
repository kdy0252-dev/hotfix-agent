package com.example.myagent.incident.application.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisRequest;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.SourceRevision;
import com.example.myagent.incident.application.domain.model.analysis.SourceSpec;
import com.example.myagent.incident.application.domain.service.internal.IncidentAnalysisExecutor;
import com.example.myagent.incident.application.domain.service.support.IncidentRequestHash;
import com.example.myagent.incident.application.port.in.AnalyzeIncidentUseCase.AnalysisCommand;
import com.example.myagent.incident.application.port.in.IncidentUseCaseException;
import com.example.myagent.incident.application.port.out.IncidentStatePort;
import io.vavr.control.Either;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

class IncidentAnalysisServiceTest {

    @Test
    void rejectsAnIneligibleJenkinsBuildBeforeCreatingAsyncState() {
        var executor = mock(IncidentAnalysisExecutor.class);
        var statePort = mock(IncidentStatePort.class);
        var service = new IncidentAnalysisService(
            executor,
            statePort,
            new CapturingTaskExecutor()
        );
        var request = new AnalysisRequest.Jenkins(
            "FMS-EU/job/main",
            181,
            SourceSpec.branch("main")
        );
        when(statePort.findAnalysisByIdempotencyKey("analysis-key"))
            .thenReturn(Either.right(Optional.empty()));
        doThrow(new IncidentUseCaseException(
            "JENKINS_BUILD_NOT_ELIGIBLE",
            "Jenkins build is not a failed build"
        )).when(executor).validateJenkinsEligibility(request);

        assertThatThrownBy(() -> service.analyzeJenkins(new AnalysisCommand<>(
            request,
            "analysis-key"
        ))).isInstanceOf(IncidentUseCaseException.class)
            .extracting(exception -> ((IncidentUseCaseException) exception).code())
            .isEqualTo("JENKINS_BUILD_NOT_ELIGIBLE");

        verify(executor, never()).requested(any(), anyString());
        verify(statePort, never()).saveAnalysis(any());
    }

    @Test
    void replaysTheSameRequestWithoutSubmittingDuplicateWorkAndRejectsKeyReuse() {
        var executor = mock(IncidentAnalysisExecutor.class);
        var statePort = mock(IncidentStatePort.class);
        var tasks = new CapturingTaskExecutor();
        var service = new IncidentAnalysisService(executor, statePort, tasks);
        var request = new AnalysisRequest.Jenkins(
            "FMS-EU/job/main",
            181,
            SourceSpec.branch("main")
        );
        AnalysisSession requested = requested(request);
        var envelope = new IncidentStatePort.AnalysisEnvelope(
            1,
            "analysis-key",
            requested.identity().requestHash(),
            requested
        );
        when(statePort.findAnalysisByIdempotencyKey("analysis-key"))
            .thenReturn(Either.right(Optional.empty()))
            .thenReturn(Either.right(Optional.of(envelope)));
        when(executor.requested(any(), anyString())).thenReturn(requested);
        when(statePort.saveAnalysis(any())).thenReturn(Either.right(requested));

        AnalysisSession first = service.analyzeJenkins(new AnalysisCommand<>(
            request,
            "analysis-key"
        ));
        AnalysisSession replayed = service.analyzeJenkins(new AnalysisCommand<>(
            request,
            "analysis-key"
        ));

        assertThat(replayed).isEqualTo(first);
        assertThat(tasks.pending()).isEqualTo(1);
        verify(executor).requested(request, requested.identity().requestHash());

        var differentRequest = new AnalysisRequest.Jenkins(
            "FMS-EU/job/main",
            182,
            SourceSpec.branch("main")
        );
        assertThatThrownBy(() -> service.analyzeJenkins(new AnalysisCommand<>(
            differentRequest,
            "analysis-key"
        ))).isInstanceOf(IncidentUseCaseException.class)
            .hasMessageContaining("다른 분석 요청");
    }

    @Test
    void returnsRequestedStateBeforeRunningExternalAnalysis() {
        var executor = mock(IncidentAnalysisExecutor.class);
        var statePort = mock(IncidentStatePort.class);
        var tasks = new CapturingTaskExecutor();
        var service = new IncidentAnalysisService(executor, statePort, tasks);
        var request = new AnalysisRequest.Jenkins(
            "FMS-EU/job/main",
            181,
            SourceSpec.branch("main")
        );
        AnalysisSession requested = requested(request);
        AnalysisSession analyzing = withStatus(requested, AnalysisSession.Status.ANALYZING);
        AnalysisSession completed = completed(requested);
        when(statePort.findAnalysisByIdempotencyKey("analysis-key"))
            .thenReturn(Either.right(Optional.empty()));
        when(executor.requested(any(), anyString())).thenReturn(requested);
        when(executor.analyzing(requested)).thenReturn(analyzing);
        when(executor.execute(request, requested)).thenReturn(completed);
        when(statePort.saveAnalysis(any())).thenAnswer(invocation -> {
            IncidentStatePort.AnalysisEnvelope envelope = invocation.getArgument(0);
            return Either.right(envelope.session());
        });

        AnalysisSession response = service.analyzeJenkins(new AnalysisCommand<>(
            request,
            "analysis-key"
        ));

        assertThat(response.result().status()).isEqualTo(AnalysisSession.Status.ANALYSIS_REQUESTED);
        assertThat(tasks.pending()).isEqualTo(1);
        verify(executor, never()).execute(any(), any());

        tasks.runNext();

        verify(executor).execute(request, requested);
        verify(statePort).saveAnalysis(new IncidentStatePort.AnalysisEnvelope(
            1,
            "analysis-key",
            requested.identity().requestHash(),
            completed,
            request
        ));
    }

    @Test
    void resubmitsAnInterruptedAnalysisFromItsPersistedRequest() {
        var executor = mock(IncidentAnalysisExecutor.class);
        var statePort = mock(IncidentStatePort.class);
        var tasks = new CapturingTaskExecutor();
        var service = new IncidentAnalysisService(executor, statePort, tasks);
        var request = new AnalysisRequest.Jenkins(
            "FMS-EU/job/PR-1292",
            1,
            SourceSpec.pullRequest(1292)
        );
        AnalysisSession requested = requested(request);
        var envelope = new IncidentStatePort.AnalysisEnvelope(
            1,
            "analysis-key",
            requested.identity().requestHash(),
            requested,
            request
        );
        AnalysisSession analyzing = withStatus(requested, AnalysisSession.Status.ANALYZING);
        AnalysisSession completed = completed(requested);
        when(statePort.findIncompleteAnalyses()).thenReturn(Either.right(List.of(envelope)));
        when(statePort.saveAnalysis(any())).thenAnswer(invocation -> Either.right(
            invocation.<IncidentStatePort.AnalysisEnvelope>getArgument(0).session()
        ));
        when(executor.analyzing(requested)).thenReturn(analyzing);
        when(executor.execute(request, requested)).thenReturn(completed);

        int recovered = service.recoverInterruptedAnalyses();

        assertThat(recovered).isEqualTo(1);
        assertThat(tasks.pending()).isEqualTo(1);
        tasks.runNext();
        verify(executor).execute(request, requested);
    }

    private AnalysisSession requested(AnalysisRequest request) {
        return new AnalysisSession(
            new AnalysisSession.Identity("analysis-1", 1, IncidentRequestHash.calculate(request)),
            new AnalysisSession.Snapshot(
                request.source(),
                null,
                Instant.parse("2026-08-20T00:00:00Z"),
                Instant.parse("2026-08-21T00:00:00Z")
            ),
            new AnalysisSession.Result(
                AnalysisSession.Status.ANALYSIS_REQUESTED,
                List.of(),
                null
            )
        );
    }

    private AnalysisSession completed(AnalysisSession requested) {
        return new AnalysisSession(
            requested.identity(),
            new AnalysisSession.Snapshot(
                requested.snapshot().source(),
                new SourceRevision("commit-1", "main", "bitbucket:branch:main"),
                requested.snapshot().createdAt(),
                requested.snapshot().expiresAt()
            ),
            new AnalysisSession.Result(
                AnalysisSession.Status.CANDIDATES_READY,
                List.of(),
                null
            )
        );
    }

    private AnalysisSession withStatus(
        AnalysisSession session,
        AnalysisSession.Status status
    ) {
        return new AnalysisSession(
            session.identity(),
            session.snapshot(),
            new AnalysisSession.Result(status, List.of(), null)
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
