package com.example.myagent.incident.adapter.out.ai;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.example.myagent.incident.application.domain.model.hotfix.PatchArtifacts.Proposal;
import com.example.myagent.incident.application.port.out.IncidentFailure;
import com.example.myagent.incident.application.port.out.PatchProposalPort;
import io.vavr.control.Either;
import io.vavr.control.Try;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.stereotype.Component;

@Adapter
@Component
public class EmbabelPatchProposalAdapter implements PatchProposalPort {
    private final AgentPlatform agentPlatform;

    public EmbabelPatchProposalAdapter(AgentPlatform agentPlatform) {
        this.agentPlatform = agentPlatform;
    }

    @Override
    public Either<IncidentFailure, Proposal> propose(PatchRequest request) {
        return Try.of(() -> {
            var invocation = AgentInvocation.create(
                agentPlatform,
                PatchProposalResult.class
            );
            var result = invocation.run(new PatchAuthorInput(
                request.candidate(),
                request.workspace(),
                request.attempt(),
                request.previousFailure(),
                request.patchInstruction()
            )).resultOfType(PatchProposalResult.class);
            return new Proposal(result.summary(), result.updates());
        }).toEither().mapLeft(exception -> new IncidentFailure(
            "PATCH_PROPOSAL_FAILED",
            "Embabel patch author가 수정안을 만들지 못했습니다."
        ));
    }
}
