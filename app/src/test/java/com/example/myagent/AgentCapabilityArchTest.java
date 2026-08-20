package com.example.myagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.embabel.agent.api.annotation.Agent;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.vavr.control.Try;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AgentCapabilityArchTest {
    private static final int MAXIMUM_CAPABILITIES = 5;

    @Test
    void everyEmbabelAgentMustHaveAtMostFiveSkillsAndFiveTools() {
        CapabilityCatalog catalog = loadCatalog();
        Set<String> registeredAgentNames = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.example.myagent")
            .stream()
            .filter(type -> type.isAnnotatedWith(Agent.class))
            .map(type -> type.getAnnotationOfType(Agent.class).name())
            .collect(Collectors.toSet());
        Set<String> manifestAgentNames = catalog.agents().stream()
            .map(AgentCapabilities::name)
            .collect(Collectors.toSet());

        assertThat(manifestAgentNames).isEqualTo(registeredAgentNames);
        assertThat(catalog.agents()).extracting(AgentCapabilities::name).doesNotHaveDuplicates();
        assertThat(catalog.agents())
            .allSatisfy(agent -> {
                assertThat(agent.skills())
                    .as("%s skills", agent.name())
                    .hasSizeLessThanOrEqualTo(MAXIMUM_CAPABILITIES)
                    .doesNotHaveDuplicates();
                assertThat(agent.tools())
                    .as("%s tools", agent.name())
                    .hasSizeLessThanOrEqualTo(MAXIMUM_CAPABILITIES)
                    .doesNotHaveDuplicates();
            });
        assertParentChildOwnership(catalog);
    }

    private void assertParentChildOwnership(CapabilityCatalog catalog) {
        var byName = catalog.agents().stream()
            .collect(Collectors.toMap(AgentCapabilities::name, agent -> agent));
        catalog.agents().stream()
            .filter(agent -> agent.parent() != null)
            .forEach(child -> {
                assertThat(byName)
                    .as("parent of %s", child.name())
                    .containsKey(child.parent());
                assertThat(child.tools())
                    .as("%s must not share child-owned tools with parent %s",
                        child.name(), child.parent())
                    .doesNotContainAnyElementsOf(byName.get(child.parent()).tools());
            });
    }

    private CapabilityCatalog loadCatalog() {
        URL resource = getClass().getResource("/agent-capabilities.json");
        assertThat(resource).isNotNull();
        return Try.of(() -> new ObjectMapper().readValue(
            Path.of(resource.toURI()),
            CapabilityCatalog.class
        )).getOrElseThrow(exception -> new IllegalStateException(
            "Cannot read agent capability manifest",
            exception
        ));
    }

    private record CapabilityCatalog(List<AgentCapabilities> agents) {
    }

    private record AgentCapabilities(
        String name,
        String parent,
        List<String> skills,
        List<String> tools
    ) {
    }
}
