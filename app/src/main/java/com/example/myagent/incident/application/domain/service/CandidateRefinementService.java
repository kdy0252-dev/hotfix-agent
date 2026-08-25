package com.example.myagent.incident.application.domain.service;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.CandidateRefinementTask;
import com.example.myagent.incident.application.domain.model.analysis.CandidateRefinementTask.Status;
import com.example.myagent.incident.application.domain.service.internal.CandidateRefinementExecutor;
import com.example.myagent.incident.application.port.in.IncidentUseCaseException;
import com.example.myagent.incident.application.port.in.RecoverCandidateRefinementUseCase;
import com.example.myagent.incident.application.port.in.RefineCandidateUseCase;
import com.example.myagent.incident.application.port.out.CandidateRefinementTaskPort;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import com.example.myagent.incident.application.port.out.IncidentStatePort;
import io.vavr.control.Try;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class CandidateRefinementService implements RefineCandidateUseCase,
    RecoverCandidateRefinementUseCase {
    private final IncidentStatePort statePort;
    private final CandidateRefinementTaskPort taskPort;
    private final CandidateRefinementExecutor executor;
    private final TaskExecutor taskExecutor;
    private final Clock clock;
    private final Set<String> activeTasks = ConcurrentHashMap.newKeySet();

    public CandidateRefinementService(
        IncidentStatePort statePort,
        CandidateRefinementTaskPort taskPort,
        CandidateRefinementExecutor executor,
        TaskExecutor taskExecutor,
        Clock clock
    ) {
        this.statePort = statePort;
        this.taskPort = taskPort;
        this.executor = executor;
        this.taskExecutor = taskExecutor;
        this.clock = clock;
    }

    @Override
    public synchronized AnalysisSession refine(RefinementCommand command) {
        var envelope = analysis(command.analysisId());
        AnalysisSession session = envelope.session();
        if (session.identity().version() != command.analysisVersion()) {
            throw exception("ANALYSIS_VERSION_CONFLICT", "분석 결과가 갱신되었습니다. 새로고침 후 다시 요청하세요.");
        }
        requireCandidate(session, command.candidateId());
        boolean anotherTaskActive = taskPort.findByAnalysisId(command.analysisId())
            .getOrElseThrow(this::failure)
            .stream()
            .anyMatch(task -> task.status().active()
                && !task.candidateId().equals(command.candidateId()));
        if (anotherTaskActive) {
            throw exception(
                "REFINEMENT_ALREADY_RUNNING",
                "같은 분석의 다른 후보를 정밀 분석하고 있습니다. 완료 후 다시 요청하세요."
            );
        }
        Instant now = clock.instant();
        var task = new CandidateRefinementTask(
            taskId(command.analysisId(), command.candidateId()),
            command.analysisId(),
            command.candidateId(),
            Status.REQUESTED,
            null,
            now,
            now
        );
        taskPort.save(task).getOrElseThrow(this::failure);
        submit(task);
        return session;
    }

    @Override
    public int recoverInterruptedRefinements() {
        var tasks = taskPort.findIncomplete().getOrElseThrow(this::failure);
        tasks.forEach(this::submit);
        return tasks.size();
    }

    private void submit(CandidateRefinementTask task) {
        if (!activeTasks.add(task.taskId())) {
            return;
        }
        Try.run(() -> taskExecutor.execute(() -> Try.run(() -> executor.execute(task))
                .andFinally(() -> activeTasks.remove(task.taskId()))
                .get()))
            .onFailure(exception -> activeTasks.remove(task.taskId()));
    }

    private IncidentStatePort.AnalysisEnvelope analysis(String analysisId) {
        return statePort.findAnalysis(analysisId)
            .getOrElseThrow(this::failure)
            .orElseThrow(() -> exception("ANALYSIS_NOT_FOUND", "분석 작업을 찾지 못했습니다."));
    }

    private void requireCandidate(AnalysisSession session, String candidateId) {
        boolean exists = session.result().candidates().stream()
            .anyMatch(candidate -> candidate.identity().candidateId().equals(candidateId));
        if (!exists) {
            throw exception("CANDIDATE_NOT_FOUND", "정밀 분석할 후보를 찾지 못했습니다.");
        }
    }

    private String taskId(String analysisId, String candidateId) {
        return analysisId + ':' + candidateId;
    }

    private IncidentUseCaseException failure(IncidentFailure value) {
        return exception(value.code(), value.message());
    }

    private IncidentUseCaseException exception(String code, String message) {
        return new IncidentUseCaseException(code, message);
    }
}
