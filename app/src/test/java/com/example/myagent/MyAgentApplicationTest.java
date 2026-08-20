package com.example.myagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.embabel.agent.config.models.openai.OpenAiProperties;
import com.embabel.agent.core.AgentPlatform;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

@SpringBootTest(properties = {
    "embabel.agent.platform.models.openai.api-key=test-api-key",
    "spring.main.web-application-type=none"
})
class MyAgentApplicationTest {

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    Environment environment;

    @Test
    void loadsAgentPlatformAndOpenAiConfiguration() {
        assertThat(applicationContext.getBean(AgentPlatform.class)).isNotNull();
        assertThat(applicationContext.getBean(OpenAiProperties.class).getApiKey())
            .isEqualTo("test-api-key");
    }

    @Test
    void disablesPromptAndCompletionContentInAiObservations() {
        assertThat(environment.getProperty(
            "spring.ai.chat.observations.log-prompt",
            Boolean.class
        )).isFalse();
        assertThat(environment.getProperty(
            "spring.ai.chat.observations.log-completion",
            Boolean.class
        )).isFalse();
        assertThat(environment.getProperty(
            "spring.ai.chat.client.observations.log-prompt",
            Boolean.class
        )).isFalse();
        assertThat(environment.getProperty(
            "spring.ai.chat.client.observations.log-completion",
            Boolean.class
        )).isFalse();
    }
}
