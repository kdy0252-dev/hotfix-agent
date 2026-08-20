package com.example.conventions

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.openrewrite.gradle.RewriteExtension

class SpringOpenRewriteConventions : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        plugins.apply("com.example.conventions.java")

        plugins.apply("org.openrewrite.rewrite")

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

        dependencies {
            add("rewrite", platform(libs.findLibrary("rewrite-recipe-bom").get()))
            add("rewrite", "org.openrewrite.recipe:rewrite-migrate-java")
            add("rewrite", "org.openrewrite.recipe:rewrite-static-analysis")
            add("rewrite", "org.openrewrite.recipe:rewrite-testing-frameworks")

            add("rewrite", platform(libs.findLibrary("rewrite-bom").get()))
            add("rewrite", "org.openrewrite:rewrite-gradle")
            add("rewrite", "org.openrewrite:rewrite-java")

            add("rewrite", libs.findLibrary("rewrite-third-party").get())
        }

        extensions.configure(RewriteExtension::class.java) {
            // 주석 되어 있는 항목들은 JDK25로 업그레이드 후 주석 해제한다.

            activeRecipe("org.openrewrite.java.format.AutoFormat")

            activeRecipe("org.openrewrite.java.migrate.UpgradeToJava21")

            activeRecipe("org.openrewrite.gradle.EnableGradleBuildCache")
            activeRecipe("org.openrewrite.gradle.DependencyConstraintToRule")
            activeRecipe("org.openrewrite.gradle.DependencyUseStringNotation")
            activeRecipe("org.openrewrite.gradle.MigrateToGradle9")

            activeRecipe("org.openrewrite.java.RemoveUnusedImports")                   // 미사용 import 제거
            activeRecipe("org.openrewrite.java.migrate.lang.UseTextBlocks")            // 문자열 → Text Block 사용 (가능한 경우)

            // -- testing --
            activeRecipe("org.openrewrite.java.testing.junit5.JUnit5BestPractices")
            activeRecipe("org.openrewrite.java.testing.assertj.AdoptAssertJDurationAssertions")                                     // Duration 비교를 AssertJ 전용 API(예: isCloseTo)로 변경
            activeRecipe("org.openrewrite.java.testing.assertj.CollapseConsecutiveAssertThatStatements")                           // 연속된 assertThat 체인을 하나로 병합해 간결화
            activeRecipe("org.openrewrite.java.testing.assertj.IsEqualToIgnoringMillisToIsCloseToRecipe")                          // isEqualToIgnoringMillis → isCloseTo 로 교체(더 명시적)
            activeRecipe("org.openrewrite.java.testing.assertj.JUnitAssertArrayEqualsToAssertThat")                                // JUnit assertArrayEquals → AssertJ assertThat().containsExactly 등
            activeRecipe("org.openrewrite.java.testing.assertj.JUnitAssertEqualsToAssertThat")                                     // JUnit assertEquals → AssertJ assertThat().isEqualTo
            activeRecipe("org.openrewrite.java.testing.assertj.JUnitAssertFalseToAssertThat")                                      // JUnit assertFalse → AssertJ assertThat().isFalse
            activeRecipe("org.openrewrite.java.testing.assertj.JUnitAssertInstanceOfToAssertThat")                                 // assertTrue(x instanceof Y) → assertThat(x).isInstanceOf(Y)
            activeRecipe("org.openrewrite.java.testing.assertj.JUnitAssertNotEqualsToAssertThat")                                  // JUnit assertNotEquals → AssertJ assertThat().isNotEqualTo
            activeRecipe("org.openrewrite.java.testing.assertj.JUnitAssertNotNullToAssertThat")                                    // JUnit assertNotNull → AssertJ assertThat().isNotNull
            activeRecipe("org.openrewrite.java.testing.assertj.JUnitAssertNullToAssertThat")                                       // JUnit assertNull → AssertJ assertThat().isNull
            activeRecipe("org.openrewrite.java.testing.assertj.JUnitAssertSameToAssertThat")                                       // JUnit assertSame → AssertJ assertThat().isSameAs
            activeRecipe("org.openrewrite.java.testing.assertj.JUnitAssertThrowsToAssertExceptionType")                            // JUnit assertThrows → AssertJ assertThatThrownBy/assertThatExceptionOfType
            activeRecipe("org.openrewrite.java.testing.assertj.JUnitAssertTrueToAssertThat")                                       // JUnit assertTrue → AssertJ assertThat().isTrue
            activeRecipe("org.openrewrite.java.testing.assertj.JUnitFailToAssertJFail")                                            // JUnit fail() → AssertJ Assertions.fail()(JDK25)
            activeRecipe("org.openrewrite.java.testing.assertj.JUnitTryFailToAssertThatThrownBy")                                  // try-catch + fail 패턴 → assertThatThrownBy 로 변환(JDK25)
            activeRecipe("org.openrewrite.java.testing.assertj.SimplifyAssertJAssertion")                                          // 장황한 AssertJ 표현을 간단한 체인으로 축약
            activeRecipe("org.openrewrite.java.testing.assertj.SimplifyChainedAssertJAssertion")                                   // 불필요한 체인 단계 제거(가독성 향상)
            activeRecipe("org.openrewrite.java.testing.assertj.SimplifyHasSizeAssertion")                                          // size 비교를 hasSize() 등 전용 API로 변환
            activeRecipe("org.openrewrite.java.testing.assertj.SimplifyRedundantAssertJChains")                                    // 중복되거나 효과 없는 체인 호출 제거(JDK25)
            activeRecipe("org.openrewrite.java.testing.assertj.AssertJBigIntegerRulesRecipes")                                      // BigInteger 검사 전용 규칙 번들(하위 $들을 포함)
            activeRecipe("org.openrewrite.java.testing.assertj.AssertJByteRulesRecipes")                                            // Byte 검사 전용 규칙 번들
            activeRecipe("org.openrewrite.java.testing.assertj.AssertJDoubleRulesRecipes")                                          // Double 검사 전용 규칙 번들
            activeRecipe("org.openrewrite.java.testing.assertj.AssertJFloatRulesRecipes")                                           // Float 검사 전용 규칙 번들
            activeRecipe("org.openrewrite.java.testing.assertj.AssertJIntegerRulesRecipes")                                         // Integer 검사 전용 규칙 번들
            activeRecipe("org.openrewrite.java.testing.assertj.AssertJLongRulesRecipes")                                            // Long 검사 전용 규칙 번들
            activeRecipe("org.openrewrite.java.testing.assertj.AssertJShortRulesRecipes")                                           // Short 검사 전용 규칙 번들
            activeRecipe("org.openrewrite.java.testing.cleanup.AssertEqualsBooleanToAssertBoolean")                                  // assertEquals(true/false, x) → assertTrue/assertFalse
            activeRecipe("org.openrewrite.java.testing.cleanup.AssertEqualsNullToAssertNull")                                       // assertEquals(null, x) → assertNull
            activeRecipe("org.openrewrite.java.testing.cleanup.AssertFalseEqualsToAssertNotEquals")                                 // assertFalse(a.equals(b)) → assertNotEquals
            activeRecipe("org.openrewrite.java.testing.cleanup.AssertFalseNegationToAssertTrue")                                    // assertFalse(!x) → assertTrue(x)
            activeRecipe("org.openrewrite.java.testing.cleanup.AssertFalseNullToAssertNotNull")                                     // assertFalse(x == null) → assertNotNull
            activeRecipe("org.openrewrite.java.testing.cleanup.AssertLiteralBooleanRemovedRecipe")                                  // assertTrue(true)/assertFalse(false) 제거
            activeRecipe("org.openrewrite.java.testing.cleanup.AssertLiteralBooleanToFailRecipes")                                  // 무의미한 assertTrue(false) → 실패(Assertions.fail)로 변경 번들
            activeRecipe("org.openrewrite.java.testing.cleanup.AssertNotEqualsBooleanToAssertBoolean")                              // assertNotEquals(true/false, x) → assertFalse/assertTrue
            activeRecipe("org.openrewrite.java.testing.cleanup.AssertTrueComparisonToAssertEquals")                                 // assertTrue(a == b) → assertEquals(a, b)
            activeRecipe("org.openrewrite.java.testing.cleanup.AssertTrueEqualsToAssertEquals")                                     // assertTrue(a.equals(b)) → assertEquals(a, b)
            activeRecipe("org.openrewrite.java.testing.cleanup.AssertTrueNegationToAssertFalse")                                    // assertTrue(!x) → assertFalse(x)
            activeRecipe("org.openrewrite.java.testing.cleanup.AssertTrueNullToAssertNull")                                         // assertTrue(x == null) → assertNull
            activeRecipe("org.openrewrite.java.testing.cleanup.AssertionsArgumentOrder")                                            // assert 계열(expected, actual) 인자 순서 교정
            activeRecipe("org.openrewrite.java.testing.cleanup.RemoveEmptyTests")                                                   // 내용 없는 테스트 제거
            activeRecipe("org.openrewrite.java.testing.cleanup.RemoveTestPrefix")                                                   // 테스트 메서드명 접두어 'test' 제거(현대 컨벤션)
            activeRecipe("org.openrewrite.java.testing.cleanup.SimplifyTestThrows")                                                // @Test(expected=...) 등 예외 검증 단순화
            activeRecipe("org.openrewrite.java.testing.cleanup.TestMethodsShouldBeVoid")                                           // 테스트 메서드는 void 반환으로 강제(JDK25)
            activeRecipe("org.openrewrite.java.testing.cleanup.TestsShouldIncludeAssertions")                                      // assertion 없는 테스트 감지/보완
            activeRecipe("org.openrewrite.java.testing.cleanup.TestsShouldNotBePublic")                                            // 테스트 메서드는 public 금지(패키지 프라이빗/기본 접근)

            // --- testing (mokito) ---
            activeRecipe("org.openrewrite.java.testing.mockito.RemoveTimesZeroAndOne")                // verify(..., times(0/1)) → never()/once() 등 간결화
            activeRecipe("org.openrewrite.java.testing.mockito.SimplifyMockitoVerifyWhenGiven")       // verify/when/given 패턴 간소화
            activeRecipe("org.openrewrite.java.testing.mockito.VerifyZeroToNoMoreInteractions")       // verifyZeroInteractions → verifyNoMoreInteractions
            activeRecipe("org.openrewrite.java.testing.mockito.MockitoWhenOnStaticToMockStatic")      // when(Static.method) → mockStatic API로 전환
            activeRecipe("org.openrewrite.java.testing.mockito.CleanupMockitoImports")                // Mockito 관련 import 정리/중복 제거


            // --- Jakarta EE javax -> jakarta로 변경 및 jakartaEE10 적용 ---
            activeRecipe("org.openrewrite.java.migrate.jakarta.JavaxMigrationToJakarta")
            activeRecipe("org.openrewrite.java.migrate.jakarta.JakartaEE10")
            activeRecipe("org.openrewrite.java.migrate.jakarta.MigrationToJakarta10Apis")
            activeRecipe("org.openrewrite.java.migrate.jakarta.UpdateJakartaPlatform10")

            // --- 정적 분석 후 자동 적용 --
            activeRecipe("org.openrewrite.staticanalysis.CommonStaticAnalysis")                                // 정적분석 기본 번들
            activeRecipe("org.openrewrite.staticanalysis.JavaApiBestPractices")                                // JDK API 모범사례
            activeRecipe("org.openrewrite.staticanalysis.CodeCleanup")                                         // 불필요 코드 정리
            activeRecipe("org.openrewrite.staticanalysis.AddSerialAnnotationToSerialVersionUID")               // serialVersionUID 필드에 @Serial 어노테이션 추가
            activeRecipe("org.openrewrite.staticanalysis.AddSerialVersionUidToSerializable")                   // Serializable 클래스에 serialVersionUID 추가
            activeRecipe("org.openrewrite.staticanalysis.AnnotateNullableMethods")                             // 메서드 반환 타입에 @Nullable 추가
            activeRecipe("org.openrewrite.staticanalysis.AnnotateNullableParameters")                          // 메서드 파라미터에 @Nullable 추가
            activeRecipe("org.openrewrite.staticanalysis.AvoidBoxedBooleanExpressions")                        // Boolean 래퍼 타입 불필요한 사용 방지
            activeRecipe("org.openrewrite.staticanalysis.BufferedWriterCreationRecipes")                       // FileWriter 사용 시 BufferedWriter로 감싸도록 개선
            activeRecipe("org.openrewrite.staticanalysis.CombineSemanticallyEqualCatchBlocks")                 // 동일한 처리의 catch 블록 합치기
            activeRecipe("org.openrewrite.staticanalysis.CompareEnumsWithEqualityOperator")                    // enum 비교는 equals() 대신 == 사용
            activeRecipe("org.openrewrite.staticanalysis.ControlFlowIndentation")                              // 제어문(조건/루프) 들여쓰기 스타일 정리
            activeRecipe("org.openrewrite.staticanalysis.DeclarationSiteTypeVariance")                         // 제네릭 선언 시 타입 variance(공변/반공변) 정리
            activeRecipe("org.openrewrite.staticanalysis.EqualsToContentEquals")                               // String equals → contentEquals로 교체
            activeRecipe("org.openrewrite.staticanalysis.ExplicitCharsetOnStringGetBytes")                     // String.getBytes()에 Charset 명시
            activeRecipe("org.openrewrite.staticanalysis.ExplicitLambdaArgumentTypes")                         // 람다 파라미터에 타입 명시
            activeRecipe("org.openrewrite.staticanalysis.FinalizeLocalVariables")                              // 지역 변수에 final 추가
            activeRecipe("org.openrewrite.staticanalysis.FinalizeMethodArguments")                             // 메서드 파라미터에 final 추가
            activeRecipe("org.openrewrite.staticanalysis.HiddenField")                                         // 지역변수/파라미터가 필드명을 가리는 경우 수정
            activeRecipe("org.openrewrite.staticanalysis.InstanceOfPatternMatch")                              // instanceof + 캐스팅 → 패턴 매칭 문법으로 변경
            activeRecipe("org.openrewrite.staticanalysis.LowercasePackage")                                    // 패키지 이름을 소문자로 강제
            activeRecipe("org.openrewrite.staticanalysis.MaskCreditCardNumbers")                               // 문자열에서 카드번호 감지 시 마스킹
            activeRecipe("org.openrewrite.staticanalysis.MissingOverrideAnnotation")                           // 오버라이드된 메서드에 @Override 추가
            activeRecipe("org.openrewrite.staticanalysis.MoveConditionsToWhile")                               // 반복문 조건을 while로 이동 (for 단순화) (JDK25)
            activeRecipe("org.openrewrite.staticanalysis.NullableOnMethodReturnType")                          // 메서드 반환 타입에 @Nullable 표시
            activeRecipe("org.openrewrite.staticanalysis.OnlyCatchDeclaredExceptions")                         // 메서드 throws에 선언된 예외만 catch
            activeRecipe("org.openrewrite.staticanalysis.PreferEqualityComparisonOverDifferenceCheck")         // 차이 비교(difference check) 대신 == 비교 사용(JDK25)
            activeRecipe("org.openrewrite.staticanalysis.PreferIncrementOperator")                             // +=1 대신 ++ 연산자 사용 (JDK25)
            activeRecipe("org.openrewrite.staticanalysis.ReferentialEqualityToObjectEquals")                   // 객체 동일성(==) 비교 → equals()로 변경
            activeRecipe("org.openrewrite.staticanalysis.RemoveCallsToObjectFinalize")                         // Object.finalize() 직접 호출 제거
            activeRecipe("org.openrewrite.staticanalysis.RemoveCallsToSystemGc")                               // System.gc() 호출 제거
            activeRecipe("org.openrewrite.staticanalysis.RemoveEmptyJavaDocParameters")                        // Javadoc의 빈 @param 제거
            activeRecipe("org.openrewrite.staticanalysis.RemoveHashCodeCallsFromArrayInstances")               // 배열에서 hashCode() 호출 제거
            activeRecipe("org.openrewrite.staticanalysis.RemoveInstanceOfPatternMatch")                        // 불필요한 instanceof 패턴 매칭 제거
            activeRecipe("org.openrewrite.staticanalysis.RemoveJavaDocAuthorTag")                              // Javadoc의 @author 태그 제거
            activeRecipe("org.openrewrite.staticanalysis.RemoveRedundantTypeCast")                             // 불필요한 타입 캐스팅 제거
            activeRecipe("org.openrewrite.staticanalysis.RemoveSystemOutPrintln")                              // System.out.println 제거
            activeRecipe("org.openrewrite.staticanalysis.RemoveToStringCallsFromArrayInstances")               // 배열에서 toString() 호출 제거
            activeRecipe("org.openrewrite.staticanalysis.RemoveUnneededAssertion")                             // 불필요한 assert 제거
            activeRecipe("org.openrewrite.staticanalysis.RemoveUnneededBlock")                                 // 불필요한 중괄호 블록 제거
            activeRecipe("org.openrewrite.staticanalysis.RemoveUnusedLocalVariables")                          // 사용되지 않는 지역 변수 제거
            activeRecipe("org.openrewrite.staticanalysis.RemoveUnusedPrivateFields")                           // 사용되지 않는 private 필드 제거
            activeRecipe("org.openrewrite.staticanalysis.RemoveUnusedPrivateMethods")                          // 사용되지 않는 private 메서드 제거
            activeRecipe("org.openrewrite.staticanalysis.RenameExceptionInEmptyCatch")                         // 빈 catch 블록의 예외 변수명 개선
            activeRecipe("org.openrewrite.staticanalysis.RenameLocalVariablesToCamelCase")                     // 지역 변수명을 카멜케이스로 변경
            activeRecipe("org.openrewrite.staticanalysis.ReorderAnnotations")                                  // 어노테이션 순서 정리
            activeRecipe("org.openrewrite.staticanalysis.ReplaceCollectionToArrayArgWithEmptyArray")           // toArray(null) → toArray(new T[0]) 변경
            activeRecipe("org.openrewrite.staticanalysis.ReplaceDeprecatedRuntimeExecMethods")                 // Runtime.exec의 deprecated 메서드 대체
            activeRecipe("org.openrewrite.staticanalysis.ReplaceDuplicateStringLiterals")                      // 중복 문자열을 상수로 추출
            activeRecipe("org.openrewrite.staticanalysis.ReplaceOptionalIsPresentWithIfPresent")               // Optional.isPresent() → ifPresent()
            activeRecipe("org.openrewrite.staticanalysis.ReplaceRedundantFormatWithPrintf")                    // String.format → System.out.printf
            activeRecipe("org.openrewrite.staticanalysis.ReplaceTextBlockWithString")                          // 불필요한 Text Block을 일반 문자열로 변경
            activeRecipe("org.openrewrite.staticanalysis.ReplaceValidateNotNullHavingVarargsWithObjectsRequireNonNull") // 가변인자 null 체크 → Objects.requireNonNull
            activeRecipe("org.openrewrite.staticanalysis.ReplaceWeekYearWithYear")                             // WeekYear 대신 Year 사용
//            activeRecipe("org.openrewrite.staticanalysis.SimplifyBooleanExpressionWithDeMorgan")               // 드모르간 법칙 적용해 불필요 논리식 단순화
            activeRecipe("org.openrewrite.staticanalysis.SimplifyCompoundStatement")                           // 복합문(if/else 등) 단순화
            activeRecipe("org.openrewrite.staticanalysis.SimplifyConsecutiveAssignments")                      // 연속된 변수 대입문 단순화
            activeRecipe("org.openrewrite.staticanalysis.SimplifyConstantIfBranchExecution")                   // 항상 true/false 조건 분기 제거
            activeRecipe("org.openrewrite.staticanalysis.SimplifyDurationCreationUnits")                       // Duration.ofX 단순화
            activeRecipe("org.openrewrite.staticanalysis.SimplifyElseBranch")                                  // else 블록 단순화(JDK25)
            activeRecipe("org.openrewrite.staticanalysis.SortedSetStreamToLinkedHashSet")                      // Stream + SortedSet → LinkedHashSet
            activeRecipe("org.openrewrite.staticanalysis.TernaryOperatorsShouldNotBeNested")                   // 삼항연산자 중첩 금지
            activeRecipe("org.openrewrite.staticanalysis.URLEqualsHashCodeRecipes")                            // URL equals/hashCode 문제 개선
            activeRecipe("org.openrewrite.staticanalysis.UnnecessaryCatch")                                    // 불필요한 catch 제거
            activeRecipe("org.openrewrite.staticanalysis.UnwrapElseAfterReturn")                               // return 이후 불필요한 else 제거
            activeRecipe("org.openrewrite.staticanalysis.UnwrapRepeatableAnnotations")                         // 중복 선언된 반복가능 어노테이션 정리
            activeRecipe("org.openrewrite.staticanalysis.UseAsBuilder")                                        // 빌더 패턴 사용 권장
            activeRecipe("org.openrewrite.staticanalysis.UseCollectionInterfaces")                             // 컬렉션 인터페이스 타입 사용
            activeRecipe("org.openrewrite.staticanalysis.UseForEachRemoveInsteadOfSetRemoveAll")               // Set.removeAll 대신 안전한 forEach.remove 사용
            activeRecipe("org.openrewrite.staticanalysis.UseLambdaForFunctionalInterface")                     // 함수형 인터페이스는 람다로 작성
            activeRecipe("org.openrewrite.staticanalysis.UseListSort")                                         // Collections.sort → List.sort
            activeRecipe("org.openrewrite.staticanalysis.UseObjectNotifyAll")                                  // notify() 대신 notifyAll() 사용
            activeRecipe("org.openrewrite.staticanalysis.UseStandardCharset")                                  // Charset.forName → StandardCharsets
            activeRecipe("org.openrewrite.staticanalysis.UseStringReplace")                                    // replaceAll(regex) → replace (단순 치환)
            activeRecipe("org.openrewrite.staticanalysis.UseSystemLineSeparator")                              // 줄바꿈은 System.lineSeparator() 사용

            // --- ErrorProne 자동 변환 ---
            activeRecipe("tech.picnic.errorprone.refasterrules.AssertJBigDecimalRulesRecipes")     // AssertJ BigDecimal 단언 패턴 표준화
            activeRecipe("tech.picnic.errorprone.refasterrules.AssertJBigIntegerRulesRecipes")     // AssertJ BigInteger 단언 패턴 표준화
            activeRecipe("tech.picnic.errorprone.refasterrules.AssertJBooleanRulesRecipes")        // AssertJ boolean 단언(isTrue/isFalse 등) 정리
            activeRecipe("tech.picnic.errorprone.refasterrules.AssertJByteRulesRecipes")           // AssertJ byte 단언 표준화
            activeRecipe("tech.picnic.errorprone.refasterrules.AssertJCharSequenceRulesRecipes")   // AssertJ CharSequence(문자열류) 크기/공백 단언 통일
            activeRecipe("tech.picnic.errorprone.refasterrules.AssertJComparableRulesRecipes")     // AssertJ Comparable 비교 단언(isGreaterThan 등) 통일
            activeRecipe("tech.picnic.errorprone.refasterrules.AssertJDoubleRulesRecipes")         // AssertJ double 단언(근사치/동등 등) 표준화
            activeRecipe("tech.picnic.errorprone.refasterrules.AssertJDurationRulesRecipes")       // AssertJ Duration 단언(초/나노/음수/양수) 통일
            activeRecipe("tech.picnic.errorprone.refasterrules.AssertJEnumerableRulesRecipes")     // AssertJ Iterable/Collection 크기 관련 단언 통일
            activeRecipe("tech.picnic.errorprone.refasterrules.AssertJFloatRulesRecipes")          // AssertJ float 단언(근사치/동등 등) 표준화
            activeRecipe("tech.picnic.errorprone.refasterrules.AssertJInstantRulesRecipes")        // AssertJ Instant 전후/구간 단언 통일
            activeRecipe("tech.picnic.errorprone.refasterrules.AssertJIntegerRulesRecipes")        // AssertJ int 단언 표준화
            activeRecipe("tech.picnic.errorprone.refasterrules.AssertJIterableRulesRecipes")       // AssertJ Iterable 비었음/크기/요소 단언 정리
            activeRecipe("tech.picnic.errorprone.refasterrules.AssertJIteratorRulesRecipes")       // AssertJ Iterator next/종료 단언 정리
            activeRecipe("tech.picnic.errorprone.refasterrules.AssertJLongRulesRecipes")           // AssertJ long 단언 표준화
            activeRecipe("tech.picnic.errorprone.refasterrules.AssertJMapRulesRecipes")            // AssertJ Map 키/값/크기/존재 단언 통일
            activeRecipe("tech.picnic.errorprone.refasterrules.AssertJNumberRulesRecipes")         // AssertJ Number(양/음) 단언 통일
            activeRecipe("tech.picnic.errorprone.refasterrules.AssertJObjectRulesRecipes")         // AssertJ Object 동일/동등/해시/널 단언 정리
            activeRecipe("tech.picnic.errorprone.refasterrules.AssertJOptionalRulesRecipes")       // AssertJ Optional present/empty/contains 단언 통일
            activeRecipe("tech.picnic.errorprone.refasterrules.AssertJPathRulesRecipes")           // AssertJ Path 존재/속성 단언 통일
            activeRecipe("tech.picnic.errorprone.refasterrules.AssertJPrimitiveRulesRecipes")      // AssertJ 원시 타입 비교 단언 통일
            activeRecipe("tech.picnic.errorprone.refasterrules.AssertJRulesRecipes")               // AssertJ 전반(스트림/컬렉션 등) 단언 패턴 개선
            activeRecipe("tech.picnic.errorprone.refasterrules.AssertJShortRulesRecipes")          // AssertJ short 단언 표준화
            activeRecipe("tech.picnic.errorprone.refasterrules.AssertJStringRulesRecipes")         // AssertJ 문자열 포함/접두/접미/크기 단언 통일
            activeRecipe("tech.picnic.errorprone.refasterrules.AssertJThrowingCallableRulesRecipes")// AssertJ 예외 단언(메시지/타입) 통일
            activeRecipe("tech.picnic.errorprone.refasterrules.AssortedRulesRecipes")              // 자주 쓰는 일반 유틸/로직 리팩터 모음
            activeRecipe("tech.picnic.errorprone.refasterrules.BigDecimalRulesRecipes")            // BigDecimal 상수/생성/비교 유틸 사용 통일
            activeRecipe("tech.picnic.errorprone.refasterrules.BugCheckerRulesRecipes")            // Error Prone 테스트 헬퍼/네이밍 등 보조 규칙 변환
            activeRecipe("tech.picnic.errorprone.refasterrules.CharSequenceRulesRecipes")          // CharSequence isEmpty 등 보일러플레이트 간소화
            activeRecipe("tech.picnic.errorprone.refasterrules.ClassRulesRecipes")                 // Class API(isInstance/cast) 사용 패턴 정리
            activeRecipe("tech.picnic.errorprone.refasterrules.CollectionRulesRecipes")            // 컬렉션 반복/contains/toArray 등 표준 패턴 적용
            activeRecipe("tech.picnic.errorprone.refasterrules.ComparatorRulesRecipes")            // Comparator/정렬/최대최소 유틸 패턴 통일
            activeRecipe("tech.picnic.errorprone.refasterrules.DoubleStreamRulesRecipes")          // DoubleStream 공통 패턴(매치/정렬/빈체크) 개선
            activeRecipe("tech.picnic.errorprone.refasterrules.EqualityRulesRecipes")              // equals/부정/이중부정 등 불변식 간소화
            activeRecipe("tech.picnic.errorprone.refasterrules.FileRulesRecipes")                  // Files/Path 유틸 API로 파일 처리 표준화
            activeRecipe("tech.picnic.errorprone.refasterrules.InputStreamRulesRecipes")           // InputStream readAllBytes/transferTo 등 최신 API 사용
            activeRecipe("tech.picnic.errorprone.refasterrules.IntStreamRulesRecipes")             // IntStream 공통 패턴(매치/정렬/빈체크) 개선
            activeRecipe("tech.picnic.errorprone.refasterrules.JUnitToAssertJRulesRecipes")        // JUnit assert → AssertJ로 마이그레이션
            activeRecipe("tech.picnic.errorprone.refasterrules.Jackson3RulesRecipes")              // Jackson JsonNode 등 안전한 Optional 처리
            activeRecipe("tech.picnic.errorprone.refasterrules.LongStreamRulesRecipes")            // LongStream 공통 패턴 개선
            activeRecipe("tech.picnic.errorprone.refasterrules.MapEntryRulesRecipes")              // Map.Entry comparingByKey/value 등 정형화
            activeRecipe("tech.picnic.errorprone.refasterrules.MapRulesRecipes")                   // Map isEmpty/getOrDefault/stream 등 표준화
            activeRecipe("tech.picnic.errorprone.refasterrules.MicrometerRulesRecipes")            // Micrometer Tags.of(...) 패턴 통일
            activeRecipe("tech.picnic.errorprone.refasterrules.MockitoRulesRecipes")               // Mockito verify/never 등 관용구 정리
            activeRecipe("tech.picnic.errorprone.refasterrules.NullRulesRecipes")                  // Objects.requireNonNull/isNull 등 null 처리 통일
            activeRecipe("tech.picnic.errorprone.refasterrules.OptionalRulesRecipes")              // Optional isPresent/ifPresent/map/stream 관용구 정리
            activeRecipe("tech.picnic.errorprone.refasterrules.PatternRulesRecipes")               // Pattern.compile/asPredicate 등 성능/가독성 개선
            activeRecipe("tech.picnic.errorprone.refasterrules.PreconditionsRulesRecipes")         // requireNonNull(메시지) 등 전제조건 통일
            activeRecipe("tech.picnic.errorprone.refasterrules.PrimitiveRulesRecipes")             // 원시타입 비교/해시/바이트 변환 유틸 통일
            activeRecipe("tech.picnic.errorprone.refasterrules.RandomGeneratorRulesRecipes")       // RandomGenerator next* 표준 API 사용
            activeRecipe("tech.picnic.errorprone.refasterrules.ReactorRulesRecipes")               // Reactor Flux/Mono 조합/에러/변환 관용구 정리
            activeRecipe("tech.picnic.errorprone.refasterrules.RxJava2AdapterRulesRecipes")        // RxJava2 ↔ Reactor 변환 어댑터 사용 통일
            activeRecipe("tech.picnic.errorprone.refasterrules.StreamRulesRecipes")                // Stream 생성/필터/정렬/리듀스 관용구 정리
            activeRecipe("tech.picnic.errorprone.refasterrules.StringRulesRecipes")                // 문자열 isBlank/startsWith/indexOf 등 관용구 정리
            activeRecipe("tech.picnic.errorprone.refasterrules.SuggestedFixRulesRecipes")          // Error Prone SuggestedFix 템플릿 변환 유틸 모음
            activeRecipe("tech.picnic.errorprone.refasterrules.TestNGToAssertJRulesRecipes")       // TestNG assert → AssertJ로 마이그레이션
            activeRecipe("tech.picnic.errorprone.refasterrules.TimeRulesRecipes")                  // java.time Duration/Instant/LocalDate* API 관용구 정리
            activeRecipe("tech.picnic.errorprone.refasterrules.WebClientRulesRecipes")             // Spring WebClient get/post/patch 등 빌더 패턴 정리

        }
    }
}
