package com.example.myagent.command.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation;
import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation.Decision;
import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation.Feedback;
import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation.Metadata;
import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation.RequestFingerprint;
import com.example.myagent.command.application.domain.model.interpretation.CommandInterpretation.Timing;
import com.example.myagent.command.application.domain.model.interpretation.InterpretationStatus;
import com.example.myagent.command.application.port.in.ExecuteNaturalLanguageCommandUseCase;
import com.example.myagent.command.application.port.in.GetCommandInterpretationUseCase;
import com.example.myagent.command.application.port.in.InterpretNaturalLanguageCommandUseCase;
import com.example.myagent.global.adapter.in.web.ApiValidationExceptionHandler;
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

class NaturalLanguageCommandControllerContractTest {
    MockMvc mockMvc;
    InterpretNaturalLanguageCommandUseCase interpretUseCase;

    @BeforeEach
    void setUp() {
        interpretUseCase = mock(InterpretNaturalLanguageCommandUseCase.class);
        var controller = new NaturalLanguageCommandController(
            interpretUseCase,
            mock(GetCommandInterpretationUseCase.class),
            mock(ExecuteNaturalLanguageCommandUseCase.class)
        );
        var objectMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
        var validator = new SpringValidatorAdapter(
            Validation.buildDefaultValidatorFactory().getValidator()
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(
                new CommandExceptionHandler(),
                new ApiValidationExceptionHandler()
            )
            .setMessageConverters(new JacksonJsonHttpMessageConverter(objectMapper))
            .setValidator(validator)
            .build();
    }

    @Test
    void createsAnInterpretationWithoutExecutingIt() throws Exception {
        when(interpretUseCase.interpret(any())).thenReturn(interpretation());

        mockMvc.perform(post("/api/v1/natural-language/interpretations")
                .header("Idempotency-Key", "nl-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"main 181 빌드를 분석해줘\"}"))
            .andExpect(status().isAccepted())
            .andExpect(header().string(
                "Location",
                "/api/v1/natural-language/interpretations/interpretation-1"
            ))
            .andExpect(jsonPath("$.metadata.interpretationId").value("interpretation-1"));
    }

    @Test
    void rejectsUnknownFieldsAndOversizedText() throws Exception {
        mockMvc.perform(post("/api/v1/natural-language/interpretations")
                .header("Idempotency-Key", "nl-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"analyze\",\"executeNow\":true}"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/natural-language/interpretations")
                .header("Idempotency-Key", "nl-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + "x".repeat(2_001) + "\"}"))
            .andExpect(status().isBadRequest());
    }

    private CommandInterpretation interpretation() {
        var createdAt = Instant.parse("2026-08-20T00:00:00Z");
        return new CommandInterpretation(
            new Metadata(
                "interpretation-1",
                1,
                new RequestFingerprint("digest", "preview"),
                new Timing(createdAt, createdAt.plusSeconds(600))
            ),
            new Decision(
                InterpretationStatus.NEEDS_CLARIFICATION,
                null,
                new Feedback(List.of("buildNumber"), List.of("빌드 번호는?"), null, null),
                CommandInterpretation.PolicyPreview.fixedPolicy(),
                null
            )
        );
    }
}
