package com.cofco.qiqihar.graintrade;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {

    @Test
    void applicationModulesFollowDeclaredBoundaries() {
        ApplicationModules.of(GrainTradeApplication.class).verify();
    }

    @Test
    void domainDoesNotDependOnFrameworkTransportOrInfrastructure() {
        JavaClasses productionClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.cofco.qiqihar.graintrade");

        noClasses()
                .that().resideInAnyPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "java.sql..",
                        "javax.sql..",
                        "java.net.http..",
                        "jakarta.servlet..",
                        "jakarta.ws.rs..",
                        "..infrastructure..")
                .allowEmptyShould(true)
                .check(productionClasses);
    }
}
