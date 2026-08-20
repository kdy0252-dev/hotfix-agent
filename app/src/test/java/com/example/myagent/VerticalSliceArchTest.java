package com.example.myagent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;

@AnalyzeClasses(
    packages = "com.example.myagent",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class VerticalSliceArchTest {

    private static final String ROOT_PACKAGE = "com.example.myagent";
    private static final Set<String> SHARED_SLICES = Set.of("global", "orchestrator");

    @ArchTest
    static final ArchRule slicesMustOnlyDependOnGlobalAndOrchestrator = classes()
        .that().resideInAPackage(ROOT_PACKAGE + "..")
        .and().resideOutsideOfPackages(
            ROOT_PACKAGE + ".global..",
            ROOT_PACKAGE + ".orchestrator.."
        )
        .should(new ArchCondition<>("only depend on the same slice, global, or orchestrator") {
            @Override
            public void check(JavaClass source, ConditionEvents events) {
                String sourceSlice = sliceOf(source);
                if (sourceSlice.isEmpty()) {
                    return;
                }
                source.getDirectDependenciesFromSelf().stream()
                      .map(dependency -> dependency.getTargetClass())
                      .filter(VerticalSliceArchTest::isProjectType)
                      .filter(target -> isForbiddenDependency(sourceSlice, target))
                      .forEach(target -> events.add(SimpleConditionEvent.violated(
                          source,
                          source.getFullName() + " depends on " + target.getFullName()
                      )));
            }
        });

    private static boolean isForbiddenDependency(String sourceSlice, JavaClass target) {
        String targetSlice = sliceOf(target);
        return !targetSlice.equals(sourceSlice) && !SHARED_SLICES.contains(targetSlice);
    }

    private static boolean isProjectType(JavaClass javaClass) {
        return javaClass.getPackageName().startsWith(ROOT_PACKAGE + ".");
    }

    private static String sliceOf(JavaClass javaClass) {
        if (javaClass.getPackageName().equals(ROOT_PACKAGE)) {
            return "";
        }
        String relativePackage = javaClass.getPackageName().substring(ROOT_PACKAGE.length() + 1);
        int separatorIndex = relativePackage.indexOf('.');
        return separatorIndex < 0 ? relativePackage : relativePackage.substring(0, separatorIndex);
    }
}
