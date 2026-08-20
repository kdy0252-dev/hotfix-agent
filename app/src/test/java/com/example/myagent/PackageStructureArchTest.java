package com.example.myagent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.example.myagent.global.annotation.InternalService;
import jakarta.persistence.Embeddable;
import jakarta.persistence.MappedSuperclass;
import org.jmolecules.architecture.hexagonal.Adapter;
import org.jmolecules.ddd.annotation.AggregateRoot;
import org.jmolecules.ddd.annotation.Entity;
import org.jmolecules.ddd.annotation.ValueObject;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Component;

@AnalyzeClasses(
    packages = "com.example.myagent",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class PackageStructureArchTest {

    private static final String MODEL_PACKAGE = "..application.domain.model";
    private static final String ENTITY_PACKAGE = "..application.domain.model.entity";
    private static final String VALUE_OBJECT_PACKAGE = "..application.domain.model.vo";
    private static final String JPA_ENTITY_PACKAGE = "..adapter.out.persistence.entity";
    private static final String JPA_EMBEDDABLE_PACKAGE = "..adapter.out.persistence.entity.embeddable";
    private static final String JPA_BASE_PACKAGE = "..adapter.out.persistence.entity.base";
    private static final String APPLICATION_SERVICE_PACKAGE = "..application.domain.service";
    private static final String INTERNAL_SERVICE_PACKAGE = "..application.domain.service.internal";
    private static final DescribedPredicate<JavaClass> SOURCE_TOP_LEVEL_TYPES =
        new DescribedPredicate<>("source top-level types") {
            @Override
            public boolean test(JavaClass javaClass) {
                return javaClass.getEnclosingClass().isEmpty()
                    && !javaClass.getSimpleName().startsWith("Q");
            }
        };
    private static final ArchCondition<JavaClass> INTERNAL_SERVICE_OR_SUPPORT_TYPE =
        new ArchCondition<>("declare @InternalService or be an internal support type") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean validType = item.isAnnotatedWith(InternalService.class)
                    || item.isInterface()
                    || item.isEnum()
                    || item.reflect().isRecord()
                    || item.getSimpleName().endsWith("Context")
                    || item.getMethods().stream()
                           .allMatch(method -> method.getModifiers().contains(JavaModifier.STATIC));
                if (!validType) {
                    events.add(SimpleConditionEvent.violated(
                        item,
                        item.getFullName() + " must declare @InternalService"
                    ));
                }
            }
        };
    private static final ArchCondition<JavaClass> IMPLEMENT_INBOUND_USE_CASE =
        new ArchCondition<>("implement an inbound UseCase") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean implementsUseCase = item.getAllRawInterfaces()
                    .stream()
                    .anyMatch(type -> type.getSimpleName().endsWith("UseCase"));
                if (!implementsUseCase) {
                    events.add(SimpleConditionEvent.violated(
                        item,
                        item.getFullName() + " must implement an inbound UseCase or move to service.internal"
                    ));
                }
            }
        };
    private static final DescribedPredicate<JavaClass> ADAPTER_BOUNDARY_TYPE_NAMES =
        new DescribedPredicate<>("have an adapter boundary type name") {
            @Override
            public boolean test(JavaClass javaClass) {
                String simpleName = javaClass.getSimpleName();
                return simpleName.endsWith("Controller")
                    || simpleName.endsWith("PersistenceAdapter")
                    || simpleName.endsWith("ModuleAdapter")
                    || simpleName.endsWith("Job")
                    || simpleName.endsWith("ExceptionHandler");
            }
        };

    @ArchTest
    static final ArchRule valueObjectsMustResideInValueObjectPackages = classes()
        .that().areAnnotatedWith(ValueObject.class)
        .should().resideInAPackage(VALUE_OBJECT_PACKAGE)
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule valueObjectPackageMustOnlyContainValueObjects = classes()
        .that().resideInAPackage(VALUE_OBJECT_PACKAGE)
        .and(SOURCE_TOP_LEVEL_TYPES)
        .should().beAnnotatedWith(ValueObject.class)
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule aggregateRootsMustResideInModelPackages = classes()
        .that().areAnnotatedWith(AggregateRoot.class)
        .should().resideInAPackage(MODEL_PACKAGE)
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domainEntityPackageMustOnlyContainEntities = classes()
        .that().resideInAPackage(ENTITY_PACKAGE)
        .and(SOURCE_TOP_LEVEL_TYPES)
        .should().beAnnotatedWith(Entity.class)
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domainEntitiesMustUseEntityPackageUnlessRequiredBySealedAggregate = classes()
        .that().areAnnotatedWith(Entity.class)
        .should(new ArchCondition<>("reside in the entity package or be a sealed aggregate subtype") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean regularEntity = item.getPackageName().endsWith(".application.domain.model.entity");
                boolean sealedAggregateSubtype = item.getPackageName().endsWith(".application.domain.model")
                    && item.getRawSuperclass()
                           .map(superclass -> superclass.isAnnotatedWith(AggregateRoot.class)
                               && superclass.reflect().isSealed())
                           .orElse(false);
                if (!regularEntity && !sealedAggregateSubtype) {
                    events.add(SimpleConditionEvent.violated(
                        item,
                        item.getFullName() + " is not in a domain entity package or a sealed aggregate subtype"
                    ));
                }
            }
        })
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule directModelPackageMustOnlyContainAggregateRootsOrSealedEntities = classes()
        .that().resideInAPackage(MODEL_PACKAGE)
        .and(SOURCE_TOP_LEVEL_TYPES)
        .should(new ArchCondition<>("be an aggregate root or an entity required by a sealed aggregate") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (!item.isAnnotatedWith(AggregateRoot.class) && !item.isAnnotatedWith(Entity.class)) {
                    events.add(SimpleConditionEvent.violated(
                        item,
                        item.getFullName() + " is neither an aggregate root nor a sealed aggregate entity"
                    ));
                }
            }
        })
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule jpaEntitiesMustResideInJpaEntityPackages = classes()
        .that().areAnnotatedWith(jakarta.persistence.Entity.class)
        .should().resideInAPackage(JPA_ENTITY_PACKAGE)
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule jpaEntityPackageMustOnlyContainJpaEntities = classes()
        .that().resideInAPackage(JPA_ENTITY_PACKAGE)
        .and(SOURCE_TOP_LEVEL_TYPES)
        .should().beAnnotatedWith(jakarta.persistence.Entity.class)
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule embeddablesMustResideInEmbeddablePackages = classes()
        .that().areAnnotatedWith(Embeddable.class)
        .should().resideInAPackage(JPA_EMBEDDABLE_PACKAGE)
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule embeddablePackageMustOnlyContainEmbeddables = classes()
        .that().resideInAPackage(JPA_EMBEDDABLE_PACKAGE)
        .and(SOURCE_TOP_LEVEL_TYPES)
        .should().beAnnotatedWith(Embeddable.class)
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule mappedSuperclassesMustResideInBasePackages = classes()
        .that().areAnnotatedWith(MappedSuperclass.class)
        .should().resideInAPackage(JPA_BASE_PACKAGE)
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule basePackageMustOnlyContainMappedSuperclasses = classes()
        .that().resideInAPackage(JPA_BASE_PACKAGE)
        .and(SOURCE_TOP_LEVEL_TYPES)
        .should().beAnnotatedWith(MappedSuperclass.class)
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule springDataRepositoriesMustResideInRepositoryPackages = classes()
        .that().areAssignableTo(Repository.class)
        .should().resideInAPackage("..adapter.out.persistence.repository")
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule internalServiceTypesMustDeclareInternalService = classes()
        .that().resideInAPackage(INTERNAL_SERVICE_PACKAGE)
        .and(SOURCE_TOP_LEVEL_TYPES)
        .should(INTERNAL_SERVICE_OR_SUPPORT_TYPE)
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule topLevelServicesMustNotDeclareInternalService = noClasses()
        .that().resideInAPackage(APPLICATION_SERVICE_PACKAGE)
        .should().beAnnotatedWith(InternalService.class)
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule topLevelApplicationServiceBeansMustImplementUseCases = classes()
        .that().resideInAPackage(APPLICATION_SERVICE_PACKAGE)
        .and(SOURCE_TOP_LEVEL_TYPES)
        .and().areMetaAnnotatedWith(Component.class)
        .should(IMPLEMENT_INBOUND_USE_CASE)
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule configurationPropertiesMustNotResideInServicePackages = noClasses()
        .that().areAnnotatedWith(ConfigurationProperties.class)
        .should().resideInAPackage(APPLICATION_SERVICE_PACKAGE)
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule supportTypesMustNotBeSpringBeans = noClasses()
        .that().resideInAPackage("..application.domain.service.support")
        .should().beMetaAnnotatedWith(Component.class)
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule adapterAnnotationsMustOnlyMarkAdapterTypes = classes()
        .that().areMetaAnnotatedWith(Adapter.class)
        .should().resideInAPackage("..adapter..")
        .andShould().resideOutsideOfPackages(
            "..adapter..dto..",
            "..adapter..entity..",
            "..adapter..mapper..",
            "..adapter..projection..",
            "..adapter..repository..",
            "..adapter..type..",
            "..adapter..policy..",
            "..adapter..sort..",
            "..adapter..specification..",
            "..adapter..configuration..",
            "..adapter..validator..",
            "..adapter..client.."
        )
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule boundaryTypesMustDeclareAdapter = classes()
        .that().resideInAPackage("..adapter..")
        .and(ADAPTER_BOUNDARY_TYPE_NAMES)
        .should().beMetaAnnotatedWith(Adapter.class)
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule legacyMethodPortPackageMustRemainEmpty = noClasses()
        .should().resideInAPackage("..application.port.in.method..")
        .allowEmptyShould(true);
}
