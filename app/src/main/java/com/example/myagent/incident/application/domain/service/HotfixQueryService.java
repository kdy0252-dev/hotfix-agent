package com.example.myagent.incident.application.domain.service;

import com.example.myagent.incident.application.domain.model.hotfix.HotfixResource;
import com.example.myagent.incident.application.port.in.IncidentUseCaseException;
import com.example.myagent.incident.application.port.in.QueryHotfixUseCase;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import com.example.myagent.incident.application.port.out.IncidentStatePort;
import com.example.myagent.incident.application.port.out.IncidentStatePort.HotfixEnvelope;
import com.example.myagent.incident.application.port.out.JenkinsEvidencePort;
import org.springframework.stereotype.Service;

@Service
public class HotfixQueryService implements QueryHotfixUseCase {
    private final IncidentStatePort statePort;
    private final JenkinsEvidencePort jenkinsEvidencePort;

    public HotfixQueryService(
        IncidentStatePort statePort,
        JenkinsEvidencePort jenkinsEvidencePort
    ) {
        this.statePort = statePort;
        this.jenkinsEvidencePort = jenkinsEvidencePort;
    }

    @Override
    public HotfixResource getHotfix(String hotfixId) {
        return envelope(hotfixId).resource();
    }

    @Override
    public HotfixResource refreshCiStatus(String hotfixId) {
        HotfixEnvelope envelope = envelope(hotfixId);
        HotfixResource resource = envelope.resource();
        String buildUrl = resource.publication().ciBuildUrl();
        if (buildUrl == null || buildUrl.isBlank()) {
            return resource;
        }
        var snapshot = jenkinsEvidencePort.refreshPullRequestBuild(buildUrl)
            .getOrElseThrow(this::failure);
        HotfixResource updated = withCiSnapshot(resource, snapshot);
        return statePort.saveHotfix(new HotfixEnvelope(
            envelope.schemaVersion(),
            envelope.idempotencyKey(),
            envelope.requestHash(),
            updated
        )).getOrElseThrow(this::failure);
    }

    private HotfixEnvelope envelope(String hotfixId) {
        return statePort.findHotfix(hotfixId)
            .getOrElseThrow(this::failure)
            .orElseThrow(() -> new IncidentUseCaseException(
                "HOTFIX_NOT_FOUND",
                "핫픽스 상태를 찾을 수 없습니다."
            ));
    }

    private HotfixResource withCiSnapshot(
        HotfixResource resource,
        JenkinsEvidencePort.CiBuildSnapshot snapshot
    ) {
        HotfixResource.Status status = "SUCCESS".equals(snapshot.result())
            ? HotfixResource.Status.RESOLVED : HotfixResource.Status.DRAFT_PR_CREATED;
        var progress = new HotfixResource.Progress(
            status,
            resource.progress().branchName(),
            resource.progress().changedFiles(),
            resource.progress().changedLines(),
            resource.progress().verification(),
            resource.progress().humanReviewReason()
        );
        var publication = new HotfixResource.Publication(
            resource.publication().draftPullRequestUrl(),
            snapshot.buildUrl(),
            snapshot.result()
        );
        return new HotfixResource(resource.identity(), progress, publication);
    }

    private IncidentUseCaseException failure(IncidentFailure incidentFailure) {
        return new IncidentUseCaseException(incidentFailure.code(), incidentFailure.message());
    }
}
