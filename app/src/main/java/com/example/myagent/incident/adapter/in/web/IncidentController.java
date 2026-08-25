package com.example.myagent.incident.adapter.in.web;

import com.example.myagent.incident.application.domain.model.analysis.AnalysisRequest;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.analysis.SourceSpec;
import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import com.example.myagent.incident.application.port.in.AnalyzeIncidentUseCase;
import com.example.myagent.incident.application.port.in.AnalyzeIncidentUseCase.AnalysisCommand;
import com.example.myagent.incident.application.port.in.QueryAnalysisUseCase;
import com.example.myagent.incident.application.port.in.QueryHotfixUseCase;
import com.example.myagent.incident.application.port.in.RefineCandidateUseCase;
import com.example.myagent.incident.application.port.in.SelectCandidateUseCase;
import com.example.myagent.incident.application.port.in.SelectCandidateUseCase.SelectionCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;
import java.util.List;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Adapter
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Incident hotfix")
@Validated
public class IncidentController {
    private final AnalyzeIncidentUseCase analyzeUseCase;
    private final QueryAnalysisUseCase queryAnalysisUseCase;
    private final SelectCandidateUseCase selectCandidateUseCase;
    private final QueryHotfixUseCase queryHotfixUseCase;
    private final RefineCandidateUseCase refineCandidateUseCase;

    public IncidentController(
        AnalyzeIncidentUseCase analyzeUseCase,
        QueryAnalysisUseCase queryAnalysisUseCase,
        SelectCandidateUseCase selectCandidateUseCase,
        QueryHotfixUseCase queryHotfixUseCase,
        RefineCandidateUseCase refineCandidateUseCase
    ) {
        this.analyzeUseCase = analyzeUseCase;
        this.queryAnalysisUseCase = queryAnalysisUseCase;
        this.selectCandidateUseCase = selectCandidateUseCase;
        this.queryHotfixUseCase = queryHotfixUseCase;
        this.refineCandidateUseCase = refineCandidateUseCase;
    }

    @PostMapping("/analyses/jenkins")
    @Operation(summary = "Analyze one explicitly selected failed Jenkins build")
    public ResponseEntity<AcceptedResource> analyzeJenkins(
        @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
        @Valid @RequestBody JenkinsAnalysisRequest request
    ) {
        var session = analyzeUseCase.analyzeJenkins(new AnalysisCommand<>(
            new AnalysisRequest.Jenkins(
                request.jobPath(),
                request.buildNumber(),
                request.source().toDomain()
            ),
            idempotencyKey
        ));
        return ResponseEntity.accepted().body(analysisAccepted(session));
    }

    @PostMapping("/analyses/observability")
    @Operation(summary = "Analyze EU app Grafana evidence in an explicit time range")
    public ResponseEntity<AcceptedResource> analyzeObservability(
        @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
        @Valid @RequestBody ObservabilityAnalysisRequest request
    ) {
        var session = analyzeUseCase.analyzeObservability(new AnalysisCommand<>(
            new AnalysisRequest.Observability(
                new AnalysisRequest.TimeRange(request.startAt(), request.endAt()),
                request.environment(),
                request.source().toDomain()
            ),
            idempotencyKey
        ));
        return ResponseEntity.accepted().body(analysisAccepted(session));
    }

    @GetMapping("/analyses/{analysisId}")
    @Operation(summary = "Get analysis status")
    public ResponseEntity<AnalysisSession> getAnalysis(
        @PathVariable @NotBlank String analysisId
    ) {
        return ResponseEntity.ok(queryAnalysisUseCase.getAnalysis(analysisId));
    }

    @GetMapping("/analyses/{analysisId}/candidates")
    @Operation(summary = "Get stable candidates for one analysis version")
    public ResponseEntity<List<BugCandidate>> getCandidates(
        @PathVariable @NotBlank String analysisId
    ) {
        return ResponseEntity.ok(queryAnalysisUseCase.getCandidates(analysisId));
    }

    @PostMapping("/analyses/{analysisId}/selections")
    @Operation(summary = "Select one eligible candidate and run the guarded hotfix workflow")
    public ResponseEntity<AcceptedResource> selectCandidate(
        @PathVariable @NotBlank String analysisId,
        @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
        @Valid @RequestBody CandidateSelectionRequest request
    ) {
        var resource = selectCandidateUseCase.select(new SelectionCommand(
            analysisId,
            request.candidateId(),
            request.analysisVersion(),
            idempotencyKey
        ));
        return ResponseEntity.accepted().body(new AcceptedResource(
            resource.identity().hotfixId(),
            resource.progress().status().name(),
            "/api/v1/hotfixes/" + resource.identity().hotfixId()
        ));
    }

    @PostMapping("/analyses/{analysisId}/candidates/{candidateId}/refinement")
    @Operation(summary = "Recheck one candidate against fresh evidence and bounded source")
    public ResponseEntity<AnalysisSession> refineCandidate(
        @PathVariable @NotBlank String analysisId,
        @PathVariable @NotBlank String candidateId,
        @Valid @RequestBody CandidateRefinementRequest request
    ) {
        return ResponseEntity.ok(refineCandidateUseCase.refine(
            new RefineCandidateUseCase.RefinementCommand(
                analysisId,
                request.analysisVersion(),
                candidateId
            )
        ));
    }

    @GetMapping("/hotfixes/{hotfixId}")
    @Operation(summary = "Get patch, verification, Draft PR and CI status")
    public ResponseEntity<HotfixResource> getHotfix(
        @PathVariable @NotBlank String hotfixId
    ) {
        return ResponseEntity.ok(queryHotfixUseCase.getHotfix(hotfixId));
    }

    @PostMapping("/hotfixes/{hotfixId}/ci-status-refresh")
    @Operation(summary = "Refresh Jenkins status once without polling or triggering a build")
    public ResponseEntity<HotfixResource> refreshCiStatus(
        @PathVariable @NotBlank String hotfixId
    ) {
        return ResponseEntity.ok(queryHotfixUseCase.refreshCiStatus(hotfixId));
    }

    public record JenkinsAnalysisRequest(
        @NotBlank String jobPath,
        @Positive long buildNumber,
        @NotNull @Valid SourceRequest source
    ) {
    }

    public record ObservabilityAnalysisRequest(
        @NotNull OffsetDateTime startAt,
        @NotNull OffsetDateTime endAt,
        @NotNull AnalysisRequest.Environment environment,
        @NotNull @Valid SourceRequest source
    ) {
    }

    public record CandidateSelectionRequest(
        @NotBlank String candidateId,
        @Positive long analysisVersion
    ) {
    }

    public record CandidateRefinementRequest(@Positive long analysisVersion) {
    }

    public record SourceRequest(
        @NotNull SourceSpec.Type type,
        String branchName,
        Long pullRequestId
    ) {
        private SourceSpec toDomain() {
            return type == SourceSpec.Type.BRANCH
                ? SourceSpec.branch(branchName) : SourceSpec.pullRequest(pullRequestId == null ? 0 : pullRequestId);
        }
    }

    public record AcceptedResource(String resourceId, String status, String statusUrl) {
    }

    private AcceptedResource analysisAccepted(AnalysisSession session) {
        String analysisId = session.identity().analysisId();
        return new AcceptedResource(
            analysisId,
            session.result().status().name(),
            "/api/v1/analyses/" + analysisId
        );
    }
}
