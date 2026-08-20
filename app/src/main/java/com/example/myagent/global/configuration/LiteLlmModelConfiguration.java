package com.example.myagent.global.configuration;

import com.embabel.agent.config.models.openai.OpenAiModelDefinitions;
import com.embabel.agent.config.models.openai.OpenAiModelLoader;
import com.embabel.common.ai.autoconfig.LlmAutoConfigMetadataLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ResourceLoader;

@Profile("local")
@Configuration(proxyBeanMethods = false)
class LiteLlmModelConfiguration {

    @Bean
    LlmAutoConfigMetadataLoader<OpenAiModelDefinitions> liteLlmModelLoader(
        ResourceLoader resourceLoader
    ) {
        return new OpenAiModelLoader(resourceLoader, "classpath:models/litellm-models.yml");
    }
}
