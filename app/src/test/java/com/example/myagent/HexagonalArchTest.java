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
public class HexagonalArchTest {

    @ArchTest
    static final ArchRule domainModelsMustNotDependOnAdapters = noClasses()
        .that().resideInAPackage("..application.domain.model..")
        .should().dependOnClassesThat().resideInAPackage("..adapter..")
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domainModelsMustNotDependOnApplicationBoundaries = noClasses()
        .that().resideInAPackage("..application.domain.model..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..application.port..",
            "..application.domain.service.."
        )
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domainModelsMustNotDependOnInfrastructureFrameworks = noClasses()
        .that().resideInAPackage("..application.domain.model..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework..",
            "jakarta.persistence..",
            "org.jooq..",
            "com.fasterxml.jackson.."
        )
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule portsMustNotDependOnAdapters = noClasses()
        .that().resideInAPackage("..application.port..")
        .should().dependOnClassesThat().resideInAPackage("..adapter..")
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule inboundAdaptersMustUseInPortsInsteadOfServices = noClasses()
        .that().resideInAPackage("..adapter.in..")
        .should().dependOnClassesThat().resideInAPackage("..application.domain.service..")
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule inboundAdaptersMustNotBypassInPortsThroughOutboundAdapters = noClasses()
        .that().resideInAPackage("..adapter.in..")
        .should().dependOnClassesThat().resideInAPackage("..adapter.out..")
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule servicesMustUseOutPortsInsteadOfAdapters = noClasses()
        .that().resideInAPackage("..application.domain.service..")
        .should().dependOnClassesThat().resideInAPackage("..adapter..")
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule outboundAdaptersMustNotDependOnServices = noClasses()
        .that().resideInAPackage("..adapter.out..")
        .should().dependOnClassesThat().resideInAPackage("..application.domain.service..")
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule outboundAdaptersMustNotDependOnInboundAdapters = noClasses()
        .that().resideInAPackage("..adapter.out..")
        .should().dependOnClassesThat().resideInAPackage("..adapter.in..")
        .allowEmptyShould(true);
}
