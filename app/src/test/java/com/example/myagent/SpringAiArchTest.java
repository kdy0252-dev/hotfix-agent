package com.example.myagent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
    packages = "com.example.myagent",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class SpringAiArchTest {

    @ArchTest
    static final ArchRule applicationCodeMustUseEmbabelInsteadOfSpringAi = noClasses()
        .that().resideInAPackage("com.example.myagent..")
        .should().dependOnClassesThat().resideInAPackage("org.springframework.ai..")
        .as("Application code must use Embabel instead of depending directly on Spring AI");

    @ArchTest
    static final ArchRule applicationCodeMustUseAnnotationAgentsInsteadOfEmbabelDsl = noClasses()
        .that().resideInAPackage("com.example.myagent..")
        .should().dependOnClassesThat().resideInAPackage("com.embabel.agent.api.dsl..")
        .as("Agents must use annotations and automatic goal planning instead of the Embabel DSL");
}
