package com.example.myagent.incident.adapter.out.ai;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.AppliedPatch;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Review;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import com.example.myagent.incident.application.port.out.PatchReviewPort;
import io.vavr.control.Either;
import io.vavr.control.Try;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.stereotype.Component;

@Adapter
@Component
public class EmbabelPatchReviewAdapter implements PatchReviewPort {
    private final AgentPlatform agentPlatform;

    public EmbabelPatchReviewAdapter(AgentPlatform agentPlatform) {
        this.agentPlatform = agentPlatform;
    }

    @Override
    public Either<IncidentFailure, Review> review(BugCandidate candidate, AppliedPatch patch) {
        return Try.of(() -> {
            var invocation = AgentInvocation.create(
                agentPlatform,
                PatchReviewResult.class
            );
            var result = invocation.run(new PatchReviewInput(
                candidate,
                patch
            )).resultOfType(PatchReviewResult.class);
            return new Review(result.approved(), result.summary(), result.findings());
        }).toEither().mapLeft(exception -> new IncidentFailure(
            "PATCH_REVIEW_FAILED",
            "Embabel patch reviewer가 수정안을 검토하지 못했습니다."
        ));
    }
}
