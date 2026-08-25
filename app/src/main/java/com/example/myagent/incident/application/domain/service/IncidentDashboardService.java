package com.example.myagent.incident.application.domain.service;

import com.example.myagent.incident.application.domain.model.dashboard.IncidentDashboardView;
import com.example.myagent.incident.application.domain.service.internal.FailedPullRequestResolver;
import com.example.myagent.incident.application.domain.service.internal.IncidentDashboardAssembler;
import com.example.myagent.incident.application.port.in.IncidentUseCaseException;
import com.example.myagent.incident.application.port.in.QueryIncidentDashboardUseCase;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import com.example.myagent.incident.application.port.out.IncidentStatePort;
import com.example.myagent.incident.application.port.out.JenkinsDashboardPort;
import com.example.myagent.incident.application.port.out.ObservabilityDashboardPort;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class IncidentDashboardService implements QueryIncidentDashboardUseCase {
    private final JenkinsDashboardPort jenkinsDashboardPort;
    private final ObservabilityDashboardPort observabilityDashboardPort;
    private final IncidentStatePort statePort;
    private final FailedPullRequestResolver pullRequestResolver;
    private final IncidentDashboardAssembler assembler;

    public IncidentDashboardService(
        JenkinsDashboardPort jenkinsDashboardPort,
        ObservabilityDashboardPort observabilityDashboardPort,
        IncidentStatePort statePort,
        FailedPullRequestResolver pullRequestResolver,
        IncidentDashboardAssembler assembler
    ) {
        this.jenkinsDashboardPort = jenkinsDashboardPort;
        this.observabilityDashboardPort = observabilityDashboardPort;
        this.statePort = statePort;
        this.pullRequestResolver = pullRequestResolver;
        this.assembler = assembler;
    }

    @Override
    public List<IncidentDashboardView.FailedPullRequest> getFailedPullRequests() {
        var builds = jenkinsDashboardPort.findFailedPullRequestBuilds()
            .getOrElseThrow(this::failure);
        return pullRequestResolver.resolve(builds);
    }

    @Override
    public List<IncidentDashboardView.ObservabilitySignal> getObservabilitySignals(
        ObservabilityQuery query
    ) {
        var signals = observabilityDashboardPort.findSignals(
            new ObservabilityDashboardPort.SignalQuery(
                query.startAt(),
                query.endAt(),
                query.environment().name()
            )
        ).getOrElseThrow(this::failure);
        return assembler.observabilitySignals(signals);
    }

    @Override
    public List<IncidentDashboardView.HotfixProgress> getHotfixProgresses() {
        var hotfixes = statePort.findAllHotfixes().getOrElseThrow(this::failure);
        return assembler.hotfixProgresses(hotfixes);
    }

    @Override
    public List<IncidentDashboardView.StoredAnalysis> getRecentAnalyses() {
        return statePort.findRecentAnalyses()
            .getOrElseThrow(this::failure)
            .stream()
            .map(assembler::storedAnalysis)
            .toList();
    }

    @Override
    public IncidentDashboardView.Analysis getAnalysis(String analysisId) {
        var envelope = statePort.findAnalysis(analysisId)
            .getOrElseThrow(this::failure)
            .orElseThrow(() -> new IncidentUseCaseException(
                "ANALYSIS_NOT_FOUND",
                "분석 결과를 찾을 수 없습니다."
            ));
        return assembler.analysis(envelope.session());
    }

    private IncidentUseCaseException failure(IncidentFailure incidentFailure) {
        return new IncidentUseCaseException(incidentFailure.code(), incidentFailure.message());
    }
}
