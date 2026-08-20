package com.example.myagent.orchestrator;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.modulith.NamedInterface;

@NamedInterface("incident-command-gateway")
public interface IncidentCommandGateway {
    ResourceResult analyzeJenkins(JenkinsCommand command);

    ResourceResult analyzeObservability(ObservabilityCommand command);

    ResourceResult listCandidates(String analysisId);

    ResourceResult selectCandidate(SelectionCommand command);

    ResourceResult getHotfix(String hotfixId);

    ResourceResult refreshCiStatus(String hotfixId);

    record Source(String type, String branchName, Long pullRequestId) {
    }

    record JenkinsCommand(
        String jobPath,
        long buildNumber,
        Source source,
        String idempotencyKey
    ) {
    }

    record ObservabilityCommand(
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        String environment,
        Source source,
        String idempotencyKey
    ) {
    }

    record SelectionCommand(
        String analysisId,
        long analysisVersion,
        String candidateId,
        String idempotencyKey
    ) {
    }

    record ResourceResult(String resourceId, String status, String statusUrl, List<String> itemIds) {
        public ResourceResult {
            itemIds = itemIds == null ? List.of() : List.copyOf(itemIds);
        }
    }
}
