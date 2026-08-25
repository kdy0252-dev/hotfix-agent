package com.example.myagent.incident.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.myagent.global.adapter.in.web.ApiValidationExceptionHandler;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession.Identity;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession.Result;
import com.example.myagent.incident.application.domain.model.analysis.AnalysisSession.Snapshot;
import com.example.myagent.incident.application.domain.model.analysis.SourceSpec;
import com.example.myagent.incident.application.port.in.AnalyzeIncidentUseCase;
import com.example.myagent.incident.application.port.in.IncidentUseCaseException;
import com.example.myagent.incident.application.port.in.QueryAnalysisUseCase;
import com.example.myagent.incident.application.port.in.QueryHotfixUseCase;
import com.example.myagent.incident.application.port.in.RefineCandidateUseCase;
import com.example.myagent.incident.application.port.in.SelectCandidateUseCase;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class IncidentControllerContractTest {
    MockMvc mockMvc;
    AnalyzeIncidentUseCase analyzeUseCase;
    RefineCandidateUseCase refineCandidateUseCase;

    @BeforeEach
    void setUp() {
        analyzeUseCase = mock(AnalyzeIncidentUseCase.class);
        refineCandidateUseCase = mock(RefineCandidateUseCase.class);
        var controller = new IncidentController(
            analyzeUseCase,
            mock(QueryAnalysisUseCase.class),
            mock(SelectCandidateUseCase.class),
            mock(QueryHotfixUseCase.class),
            refineCandidateUseCase
        );
        var objectMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
        var validator = Validation.buildDefaultValidatorFactory().getValidator();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(
                new IncidentExceptionHandler(),
                new ApiValidationExceptionHandler()
            )
            .setMessageConverters(new JacksonJsonHttpMessageConverter(objectMapper))
            .setValidator(new SpringValidatorAdapter(validator))
            .build();
    }

    @Test
    void refinesOneCandidateAgainstTheCurrentAnalysisVersion() throws Exception {
        when(refineCandidateUseCase.refine(any())).thenReturn(requestedSession());

        mockMvc.perform(post("/api/v1/analyses/analysis-1/candidates/candidate-1/refinement")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"analysisVersion\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.identity.analysisId").value("analysis-1"));
    }

    @Test
    void acceptsAValidAnalysisAsynchronously() throws Exception {
        when(analyzeUseCase.analyzeJenkins(any())).thenReturn(requestedSession());

        mockMvc.perform(post("/api/v1/analyses/jenkins")
                .header("Idempotency-Key", "request-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequest()))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.resourceId").value("analysis-1"))
            .andExpect(jsonPath("$.statusUrl").value("/api/v1/analyses/analysis-1"));
    }

    @Test
    void rejectsAMissingIdempotencyHeader() throws Exception {
        mockMvc.perform(post("/api/v1/analyses/jenkins")
            .contentType(MediaType.APPLICATION_JSON)
            .content(validRequest()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsUnknownJsonFields() throws Exception {
        mockMvc.perform(post("/api/v1/analyses/jenkins")
                .header("Idempotency-Key", "request-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "jobPath": "FMS-EU/job/main",
                      "buildNumber": 181,
                      "source": {"type": "BRANCH", "branchName": "main"},
                      "service": "OTHER"
                    }
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void mapsANonFailedJenkinsBuildToUnprocessableContent() throws Exception {
        when(analyzeUseCase.analyzeJenkins(any())).thenThrow(new IncidentUseCaseException(
            "JENKINS_BUILD_NOT_ELIGIBLE",
            "Jenkins build is not a failed build"
        ));

        mockMvc.perform(post("/api/v1/analyses/jenkins")
                .header("Idempotency-Key", "request-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequest()))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.code").value("JENKINS_BUILD_NOT_ELIGIBLE"));
    }

    private String validRequest() {
        return """
            {
              "jobPath": "FMS-EU/job/main",
              "buildNumber": 181,
              "source": {"type": "BRANCH", "branchName": "main"}
            }
            """;
    }

    private AnalysisSession requestedSession() {
        return new AnalysisSession(
            new Identity("analysis-1", 1, "request-hash"),
            new Snapshot(
                SourceSpec.branch("main"),
                null,
                Instant.parse("2026-08-20T00:00:00Z"),
                Instant.parse("2026-08-21T00:00:00Z")
            ),
            new Result(AnalysisSession.Status.ANALYSIS_REQUESTED, List.of(), null)
        );
    }
}
