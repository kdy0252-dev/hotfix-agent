package com.example.myagent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.example.myagent.global.annotation.InternalService;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;

@AnalyzeClasses(
    packages = "com.example.myagent",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class NamedInterfaceArchTest {
    private static final String ORCHESTRATOR_PACKAGE = "..orchestrator..";
    private static final String MODULE_API_SERVICE_PACKAGE = "..application.domain.service.moduleapi..";
    private static final DescribedPredicate<JavaClass> TOP_LEVEL_CLASSES =
        new DescribedPredicate<>("top-level classes") {
            @Override
            public boolean test(JavaClass javaClass) {
                return javaClass.getEnclosingClass().isEmpty();
            }
        };
    private static final DescribedPredicate<JavaClass> NAMED_INTERFACE_IMPLEMENTORS =
        new DescribedPredicate<>("implement a NamedInterface") {
            @Override
            public boolean test(JavaClass javaClass) {
                return implementsNamedInterface(javaClass);
            }
        };
    private static final ArchCondition<JavaClass> IMPLEMENT_NAMED_INTERFACE =
        new ArchCondition<>("implement at least one NamedInterface") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean implementsNamedInterface = item.getAllRawInterfaces().stream()
                    .anyMatch(type -> type.isAnnotatedWith(NamedInterface.class));
                if (!implementsNamedInterface) {
                    events.add(SimpleConditionEvent.violated(
                        item,
                        item.getFullName() + " does not implement a NamedInterface"
                    ));
                }
            }
        };
    private static final ArchCondition<JavaClass> DECLARE_NESTED_RECORD =
        new ArchCondition<>("declare at least one nested record") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (Arrays.stream(item.reflect().getDeclaredClasses()).noneMatch(Class::isRecord)) {
                    events.add(SimpleConditionEvent.violated(
                        item,
                        "No nested record declared in " + item.getFullName()
                    ));
                }
            }
        };
    private static final ArchCondition<JavaClass> HAVE_DOMAIN_SERVICE_IMPLEMENTOR =
        new ArchCondition<>("be implemented by at least one application.domain.service class") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (!hasDomainServiceImplementor(item)) {
                    events.add(SimpleConditionEvent.violated(
                        item,
                        "No application.domain.service implementor found for " + item.getFullName()
                    ));
                }
            }
        };
    private static final String ORCHESTRATOR_ROOT_PACKAGE =
        "com.example.myagent.orchestrator";
    private static final String PROJECT_ROOT_PACKAGE = "com.example.myagent";

    @ArchTest
    static final ArchRule namedInterfacesMustBeOrchestratorInterfaces = classes()
        .that().areAnnotatedWith(NamedInterface.class)
        .and().doNotHaveSimpleName("package-info")
        .should().beInterfaces()
        .andShould().resideInAPackage(ORCHESTRATOR_PACKAGE)
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule orchestratorInterfacesMustDeclareNamedInterface = classes()
        .that().resideInAPackage(ORCHESTRATOR_PACKAGE)
        .and().areInterfaces()
        .should().beAnnotatedWith(NamedInterface.class)
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule orchestratorInterfacesMustHaveDomainServiceImplementor = classes()
        .that().resideInAPackage(ORCHESTRATOR_PACKAGE)
        .and().areInterfaces()
        .and().doNotHaveSimpleName("package-info")
        .should(HAVE_DOMAIN_SERVICE_IMPLEMENTOR)
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule namedInterfaceImplementorsMustResideInModuleApiServicePackages = classes()
        .that(NAMED_INTERFACE_IMPLEMENTORS)
        .should().resideInAPackage(MODULE_API_SERVICE_PACKAGE)
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule orchestratorInterfacesMustDeclareNestedRecord = classes()
        .that().resideInAPackage(ORCHESTRATOR_PACKAGE)
        .and().areInterfaces()
        .and().doNotHaveSimpleName("package-info")
        .should(DECLARE_NESTED_RECORD)
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule moduleApiServicesMustImplementNamedInterface = classes()
        .that().resideInAPackage(MODULE_API_SERVICE_PACKAGE)
        .and(TOP_LEVEL_CLASSES)
        .should(IMPLEMENT_NAMED_INTERFACE)
        .andShould().beMetaAnnotatedWith(Service.class)
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule moduleApiServicesMustNotDeclareInternalService = noClasses()
        .that().resideInAPackage(MODULE_API_SERVICE_PACKAGE)
        .and(TOP_LEVEL_CLASSES)
        .should().beAnnotatedWith(InternalService.class)
        .allowEmptyShould(true);

    private static final ArchCondition<JavaClass> NOT_DEPEND_ON_PROJECT_MODULES =
        new ArchCondition<>("not depend on project modules outside orchestrator") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                List<JavaClass> dependencies = item.getDirectDependenciesFromSelf()
                                                   .stream()
                                                   .map(dependency -> dependency.getTargetClass())
                                                   .toList();

                dependencies.stream()
                            .filter(NamedInterfaceArchTest::isOutsideOrchestratorProjectType)
                            .forEach(dependency -> events.add(SimpleConditionEvent.violated(
                                item,
                                item.getFullName() + " depends on " + dependency.getFullName()
                            )));
            }
        };

    @ArchTest
    static final ArchRule orchestratorMustNotDependOnProjectModules = classes()
        .that().resideInAPackage(ORCHESTRATOR_PACKAGE)
        .should(NOT_DEPEND_ON_PROJECT_MODULES)
        .allowEmptyShould(true);

    private static final ArchCondition<JavaClass> ACCESS_NAMED_INTERFACE_THROUGH_MODULE_ADAPTER =
        new ArchCondition<>("access NamedInterface only through adapter.out.module") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                directlyUsedNamedInterfaces(item).stream()
                    .filter(namedInterface -> !isAllowedNamedInterfaceAccess(item, namedInterface))
                    .forEach(namedInterface -> events.add(SimpleConditionEvent.violated(
                        item,
                        item.getFullName() + " directly uses NamedInterface "
                            + namedInterface.getFullName()
                            + " outside adapter.out.module"
                    )));
            }
        };

    @ArchTest
    static final ArchRule namedInterfacesMustBeAccessedThroughModuleAdapters = classes()
        .that().resideOutsideOfPackage(ORCHESTRATOR_PACKAGE)
        .should(ACCESS_NAMED_INTERFACE_THROUGH_MODULE_ADAPTER);

    private static boolean hasDomainServiceImplementor(JavaClass javaClass) {
        return javaClass.getAllSubclasses()
                        .stream()
                        .anyMatch(type -> !type.isInterface()
                            && type.getPackageName().contains(".application.domain.service"));
    }

    private static boolean implementsNamedInterface(JavaClass javaClass) {
        return javaClass.getAllRawInterfaces()
                        .stream()
                        .anyMatch(type -> type.isAnnotatedWith(NamedInterface.class));
    }

    private static boolean isOutsideOrchestratorProjectType(JavaClass javaClass) {
        String packageName = javaClass.getPackageName();
        return packageName.startsWith(PROJECT_ROOT_PACKAGE)
            && !packageName.startsWith(ORCHESTRATOR_ROOT_PACKAGE);
    }

    private static boolean isAllowedNamedInterfaceAccess(JavaClass source, JavaClass namedInterface) {
        if (source.getPackageName().contains(".adapter.out.module")) {
            return true;
        }
        JavaClass sourceOwner = source.getEnclosingClass().orElse(source);
        return sourceOwner.getPackageName().contains(".application.domain.service")
            && sourceOwner.isAssignableTo(namedInterface.getFullName());
    }

    private static Set<JavaClass> directlyUsedNamedInterfaces(JavaClass javaClass) {
        Set<JavaClass> namedInterfaces = new LinkedHashSet<>();
        javaClass.getAllFields().stream()
                 .map(field -> field.getRawType())
                 .filter(type -> type.isAnnotatedWith(NamedInterface.class))
                 .forEach(namedInterfaces::add);
        javaClass.getMethods().forEach(method -> {
            if (method.getRawReturnType().isAnnotatedWith(NamedInterface.class)) {
                namedInterfaces.add(method.getRawReturnType());
            }
            method.getRawParameterTypes().stream()
                  .filter(type -> type.isAnnotatedWith(NamedInterface.class))
                  .forEach(namedInterfaces::add);
        });
        javaClass.getConstructors().forEach(constructor -> constructor.getRawParameterTypes().stream()
            .filter(type -> type.isAnnotatedWith(NamedInterface.class))
            .forEach(namedInterfaces::add));
        return namedInterfaces;
    }
}
