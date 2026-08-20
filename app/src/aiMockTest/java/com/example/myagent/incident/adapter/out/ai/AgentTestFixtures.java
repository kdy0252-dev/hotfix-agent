package com.example.myagent.incident.adapter.out.ai;

import com.example.myagent.incident.application.domain.model.analysis.BugCandidate;
import java.util.List;

final class AgentTestFixtures {
    private AgentTestFixtures() {
    }

    static BugCandidate eligibleCandidate() {
        return new BugCandidate(
            new BugCandidate.Identity(
                "candidate-1",
                "Null booking response",
                "BookingService dereferences a null response",
                0.95,
                BugCandidate.Eligibility.ELIGIBLE
            ),
            new BugCandidate.Evidence(
                List.of("eu/eu-app/src/main/java/BookingService.java:84"),
                List.of("jenkins:181:console"),
                List.of()
            ),
            new BugCandidate.Recommendation(
                "Guard the response",
                "Run eu-app tests and Jenkins parity"
            )
        );
    }
}
