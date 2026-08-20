package com.example.myagent.command.adapter.out.module;

import com.example.myagent.command.application.domain.model.command.CommandParameters;
import com.example.myagent.command.application.domain.model.command.InterpretedCommand;
import com.example.myagent.command.application.domain.model.command.SourceReference;
import com.example.myagent.command.application.domain.model.execution.CommandExecution;
import com.example.myagent.command.application.port.out.CommandFailure;
import com.example.myagent.command.application.port.out.ConfirmedCommandDispatchPort;
import com.example.myagent.orchestrator.IncidentCommandGateway;
import io.vavr.control.Either;
import io.vavr.control.Try;
import java.time.ZoneOffset;
import java.util.Locale;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.springframework.stereotype.Component;

@Adapter
@Component
public class IncidentCommandModuleAdapter implements ConfirmedCommandDispatchPort {
    private final IncidentCommandGateway gateway;

    public IncidentCommandModuleAdapter(IncidentCommandGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public Either<CommandFailure, CommandExecution.Result> dispatch(
        InterpretedCommand command,
        String idempotencyKey
    ) {
        return Try.of(() -> dispatchCommand(command, idempotencyKey))
            .toEither()
            .mapLeft(exception -> new CommandFailure(
                "STRUCTURED_COMMAND_FAILED",
                exception.getMessage()
            ));
    }

    private CommandExecution.Result dispatchCommand(
        InterpretedCommand command,
        String idempotencyKey
    ) {
        IncidentCommandGateway.ResourceResult result = switch (command.intent()) {
            case ANALYZE_JENKINS -> analyzeJenkins(
                (CommandParameters.JenkinsAnalysis) command.parameters(),
                idempotencyKey
            );
            case ANALYZE_OBSERVABILITY -> analyzeObservability(
                (CommandParameters.ObservabilityAnalysis) command.parameters(),
                idempotencyKey
            );
            case LIST_CANDIDATES -> gateway.listCandidates(
                ((CommandParameters.CandidateList) command.parameters()).analysisId()
            );
            case SELECT_CANDIDATE -> selectCandidate(
                (CommandParameters.CandidateSelection) command.parameters(),
                idempotencyKey
            );
            case GET_HOTFIX_STATUS -> gateway.getHotfix(
                ((CommandParameters.HotfixStatus) command.parameters()).hotfixId()
            );
            case REFRESH_CI_STATUS -> gateway.refreshCiStatus(
                ((CommandParameters.CiStatusRefresh) command.parameters()).hotfixId()
            );
        };
        return new CommandExecution.Result(
            result.resourceId(),
            result.status(),
            result.statusUrl(),
            result.itemIds()
        );
    }

    private IncidentCommandGateway.ResourceResult analyzeJenkins(
        CommandParameters.JenkinsAnalysis parameters,
        String idempotencyKey
    ) {
        return gateway.analyzeJenkins(new IncidentCommandGateway.JenkinsCommand(
            parameters.jobPath(),
            parameters.buildNumber(),
            source(parameters.source()),
            idempotencyKey
        ));
    }

    private IncidentCommandGateway.ResourceResult analyzeObservability(
        CommandParameters.ObservabilityAnalysis parameters,
        String idempotencyKey
    ) {
        return gateway.analyzeObservability(new IncidentCommandGateway.ObservabilityCommand(
            parameters.startAt().atOffset(ZoneOffset.UTC),
            parameters.endAt().atOffset(ZoneOffset.UTC),
            parameters.environment().toUpperCase(Locale.ROOT),
            source(parameters.source()),
            idempotencyKey
        ));
    }

    private IncidentCommandGateway.ResourceResult selectCandidate(
        CommandParameters.CandidateSelection parameters,
        String idempotencyKey
    ) {
        return gateway.selectCandidate(new IncidentCommandGateway.SelectionCommand(
            parameters.analysisId(),
            parameters.analysisVersion(),
            parameters.candidateId(),
            idempotencyKey
        ));
    }

    private IncidentCommandGateway.Source source(SourceReference source) {
        if (source instanceof SourceReference.Branch branch) {
            return new IncidentCommandGateway.Source("BRANCH", branch.name(), null);
        }
        return new IncidentCommandGateway.Source(
            "PULL_REQUEST",
            null,
            ((SourceReference.PullRequest) source).number()
        );
    }
}
