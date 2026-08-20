package com.example.myagent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.example.myagent.global.annotation.InternalService;
import com.example.myagent.global.annotation.ModuleBridgePort;
import io.vavr.control.Either;
import org.springframework.stereotype.Service;

@AnalyzeClasses(
    packages = "com.example.myagent",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class OutPortEitherReturnArchTest {

    @ArchTest
    static final ArchRule outPortMethodsShouldReturnEither = methods()
        .that().areDeclaredInClassesThat().resideInAPackage("..application.port.out..")
        .and().areDeclaredInClassesThat().areInterfaces()
        .and().areDeclaredInClassesThat().areNotAnnotatedWith(ModuleBridgePort.class)
        .and().arePublic()
        .should().haveRawReturnType(Either.class)
        .because("out ports must represent failures explicitly with Either")
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule applicationServicesShouldHandleEither = methods()
        .that().areDeclaredInClassesThat().areMetaAnnotatedWith(Service.class)
        .and().areDeclaredInClassesThat().areNotAnnotatedWith(InternalService.class)
        .and().arePublic()
        .should().notHaveRawReturnType(Either.class)
        .because("application services must handle out port failures at the application boundary")
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule internalServicesShouldResideInInternalServicePackages = classes()
        .that().areAnnotatedWith(InternalService.class)
        .should().resideInAPackage("..application..service.internal..")
        .because("internal services must remain inside an application service internal package")
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule internalServicesShouldOnlyBeUsedByApplicationTypes = classes()
        .that().areAnnotatedWith(InternalService.class)
        .should().onlyHaveDependentClassesThat().resideInAPackage("..application..")
        .because("internal services may only be used inside the application layer")
        .allowEmptyShould(true);
}
