package com.example.myagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.embabel.agent.config.models.openai.OpenAiProperties;
import com.embabel.agent.spi.support.LlmDataBindingProperties;
import com.embabel.common.ai.model.ConfigurableModelProviderProperties;
import com.example.myagent.global.configuration.AgentRuntimeProperties;
import com.example.myagent.global.configuration.AiInputBudgetProperties;
import com.example.myagent.global.configuration.BitbucketProperties;
import com.example.myagent.global.configuration.GrafanaProperties;
import com.example.myagent.global.configuration.JenkinsProperties;
import com.example.myagent.global.configuration.ObservabilityScopeProperties;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("local")
@SpringBootTest(properties = {
    "LITELLM_BASE_URL=https://litellm.example.com",
    "LITELLM_API_KEY=test-litellm-key",
    "LITELLM_MODEL=gemma-4.26b",
    "LITELLM_TRIAGE_MODEL=claude-haiku-4-5",
    "LITELLM_REASONING_MODEL=chatgpt-5.6-sol",
    "LITELLM_REVIEW_MODEL=claude-sonnet-5",
    "BITBUCKET_TOKEN=test-bitbucket-token",
    "JENKINS_USER=autocrypt",
    "JENKINS_TOKEN=test-jenkins-token",
    "GRAFANA_TOKEN=test-grafana-token",
    "GRAFANA_LOKI_DATASOURCE_UID=loki-test",
    "GRAFANA_PROMETHEUS_DATASOURCE_UID=prometheus-test",
    "GRAFANA_TEMPO_DATASOURCE_UID=tempo-test",
    "AGENT_FMS_REPOSITORY_PATH=/tmp/fms-test",
    "spring.main.web-application-type=none"
})
class LocalIntegrationConfigurationTest {

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    BitbucketProperties bitbucketProperties;

    @Autowired
    JenkinsProperties jenkinsProperties;

    @Autowired
    GrafanaProperties grafanaProperties;

    @Autowired
    AgentRuntimeProperties agentRuntimeProperties;

    @Autowired
    AiInputBudgetProperties aiInputBudgetProperties;

    @Autowired
    ObservabilityScopeProperties observabilityScopeProperties;

    @Autowired
    OpenAiProperties openAiProperties;

    @Autowired
    LlmDataBindingProperties llmDataBindingProperties;

    @Autowired
    ConfigurableModelProviderProperties modelProviderProperties;

    @Test
    void bindsLocalIntegrationEnvironmentVariables() {
        assertThat(bitbucketProperties.baseUrl())
            .isEqualTo(URI.create("https://api.bitbucket.org/2.0"));
        assertThat(bitbucketProperties.gitBaseUrl())
            .isEqualTo(URI.create("https://bitbucket.org"));
        assertThat(bitbucketProperties.workspace()).isEqualTo("autocrypt");
        assertThat(bitbucketProperties.repository()).isEqualTo("fms");
        assertThat(bitbucketProperties.token()).isEqualTo("test-bitbucket-token");

        assertThat(jenkinsProperties.baseUrl())
            .isEqualTo(URI.create("https://jenkins.autocrypt-fms.io"));
        assertThat(jenkinsProperties.rootJob()).isEqualTo("FMS-EU");
        assertThat(jenkinsProperties.username()).isEqualTo("autocrypt");
        assertThat(jenkinsProperties.apiToken()).isEqualTo("test-jenkins-token");
        assertThat(jenkinsProperties.tlsVerify()).isFalse();

        assertThat(grafanaProperties.baseUrl())
            .isEqualTo(URI.create("https://prod-grafana.autocrypt-fms.io"));
        assertThat(grafanaProperties.token()).isEqualTo("test-grafana-token");
        assertThat(grafanaProperties.tlsVerify()).isFalse();
        assertThat(grafanaProperties.datasourceUids().loki()).isEqualTo("loki-test");
        assertThat(grafanaProperties.datasourceUids().prometheus()).isEqualTo("prometheus-test");
        assertThat(grafanaProperties.datasourceUids().tempo()).isEqualTo("tempo-test");

        assertThat(agentRuntimeProperties.mode()).isEqualTo(AgentRuntimeProperties.Mode.REPORT_ONLY);
        assertThat(agentRuntimeProperties.fmsRepositoryPath()).isEqualTo(Path.of("/tmp/fms-test"));
        assertThat(agentRuntimeProperties.analysisTtl()).isEqualTo(Duration.ofHours(24));
        assertThat(aiInputBudgetProperties.triage().maxInputTokens()).isEqualTo(8_000);
        assertThat(aiInputBudgetProperties.triage().maxOutputTokens()).isEqualTo(1_500);
        assertThat(aiInputBudgetProperties.reasoning().maxInputTokens()).isEqualTo(16_000);
        assertThat(aiInputBudgetProperties.reasoning().maxOutputTokens()).isEqualTo(4_000);
        assertThat(aiInputBudgetProperties.review().maxInputTokens()).isEqualTo(8_000);
        assertThat(aiInputBudgetProperties.review().maxOutputTokens()).isEqualTo(1_500);
        assertThat(aiInputBudgetProperties.charactersPerToken()).isEqualTo(3);
        assertThat(openAiProperties.getMaxAttempts()).isEqualTo(1);
        assertThat(llmDataBindingProperties.getMaxAttempts()).isEqualTo(1);

        assertThat(observabilityScopeProperties.region()).isEqualTo("eu");
        assertThat(observabilityScopeProperties.application()).isEqualTo("app");
        assertThat(observabilityScopeProperties.namespaceTemplate()).isEqualTo("fms-eu-%s");
        assertThat(observabilityScopeProperties.serviceNameTemplate()).isEqualTo("fms-eu-%s-app");

        assertThat(openAiProperties.getBaseUrl()).isEqualTo("https://litellm.example.com");
        assertThat(openAiProperties.getApiKey()).isEqualTo("test-litellm-key");
        assertThat(modelProviderProperties.getDefaultLlm()).isEqualTo("gemma-4.26b");
        assertThat(modelProviderProperties.getLlms()).containsEntry(
            "triage",
            "claude-haiku-4-5"
        ).containsEntry("reasoning", "chatgpt-5.6-sol")
            .containsEntry("review", "claude-sonnet-5");
    }

    @Test
    void registersConfiguredLiteLlmModelWithEmbabel() {
        assertThat(applicationContext.containsBean("chatgpt-5.6-luna")).isTrue();
        assertThat(applicationContext.containsBean("gemma-4.26b")).isTrue();
    }
}
