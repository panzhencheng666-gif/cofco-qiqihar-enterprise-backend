package com.cofco.qiqihar.graintrade;

import com.cofco.qiqihar.graintrade.archfixture.domain.HttpDependentDomainFixture;
import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArchitectureTest {

    private static final ArchRule DOMAIN_DEPENDENCY_RULE = classes()
            .that().resideInAnyPackage("..domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "..domain..",
                    "java.lang..",
                    "java.math..",
                    "java.time..",
                    "java.util..")
            .because("domain code may only use other domain types and framework-free JDK value APIs")
            .allowEmptyShould(true);

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
}
