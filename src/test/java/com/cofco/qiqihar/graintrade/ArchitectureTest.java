package com.cofco.qiqihar.graintrade;

import com.cofco.qiqihar.graintrade.archfixture.domain.ExternalDomainDependentFixture;
import com.cofco.qiqihar.graintrade.archfixture.domain.HttpDependentDomainFixture;
import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArchitectureTest {

    private static final ArchRule DOMAIN_CLASS_DEPENDENCY_RULE = classes()
            .that().resideInAnyPackage("com.cofco.qiqihar.graintrade..domain..")
            .and().doNotHaveSimpleName("package-info")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "com.cofco.qiqihar.graintrade..domain..",
                    "java.lang..",
                    "java.math..",
                    "java.time..",
                    "java.util..")
            .because("domain code may only use other domain types and framework-free JDK value APIs")
            .allowEmptyShould(true);

    private static final ArchRule DOMAIN_PACKAGE_INFO_RULE = classes()
            .that().resideInAnyPackage("com.cofco.qiqihar.graintrade..domain..")
            .and().haveSimpleName("package-info")
            .should().onlyDependOnClassesThat(new DescribedPredicate<>(
                    "are java annotation metadata or the exact Spring Modulith NamedInterface") {
                @Override
                public boolean test(JavaClass dependency) {
                    return dependency.getPackageName().startsWith("java.lang")
                            || dependency.getName().equals(
                                    "org.springframework.modulith.NamedInterface");
                }
            })
            .because("domain package metadata has one narrow Modulith boundary exception")
            .allowEmptyShould(true);

    private static final ArchRule DOMAIN_DEPENDENCY_RULE = CompositeArchRule
            .of(DOMAIN_CLASS_DEPENDENCY_RULE)
            .and(DOMAIN_PACKAGE_INFO_RULE);

    @Test
    void applicationModulesFollowDeclaredBoundaries() {
        ApplicationModules.of(GrainTradeApplication.class).verify();
    }

    @Test
    void domainDoesNotDependOnFrameworkTransportOrInfrastructure() {
        JavaClasses productionClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.cofco.qiqihar.graintrade");

        DOMAIN_DEPENDENCY_RULE.check(productionClasses);
    }

    @Test
    void domainRuleRejectsHttpDependencyFixture() {
        JavaClasses fixtureClasses = new ClassFileImporter()
                .importClasses(HttpDependentDomainFixture.class);

        AssertionError violation = assertThrows(
                AssertionError.class,
                () -> DOMAIN_DEPENDENCY_RULE.check(fixtureClasses));

        assertThat(violation).hasMessageContaining("java.net.URI");
    }

    @Test
    void domainRuleRejectsExternalDomainPackageFixture() {
        JavaClasses fixtureClasses = new ClassFileImporter()
                .importClasses(ExternalDomainDependentFixture.class);

        AssertionError violation = assertThrows(
                AssertionError.class,
                () -> DOMAIN_DEPENDENCY_RULE.check(fixtureClasses));

        assertThat(violation)
                .hasMessageContaining("com.external.framework.domain.ExternalDomainType");
    }

    @Test
    void domainRuleRejectsExternalPackageInfoMetadata() {
        JavaClasses fixtureClasses = new ClassFileImporter()
                .importPackages(
                        "com.cofco.qiqihar.graintrade.archfixture.packageinfodomain.domain");

        AssertionError violation = assertThrows(
                AssertionError.class,
                () -> DOMAIN_DEPENDENCY_RULE.check(fixtureClasses));

        assertThat(violation)
                .hasMessageContaining("com.external.framework.ExternalPackageMetadata");
    }
}
