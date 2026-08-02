package com.cofco.qiqihar.graintrade;

import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class TestDatabaseSafetyArchitectureTest {

    private final JavaClasses testClasses = new ClassFileImporter()
            .importPackages("com.cofco.qiqihar.graintrade");

    @Test
    void everySpringBootTestUsesTheProtectedDatabaseConfiguration() {
        classes()
                .that().areAnnotatedWith(SpringBootTest.class)
                .should().beAnnotatedWith(UsesProtectedTestDatabase.class)
                .check(testClasses);
    }

    @Test
    void rawDatabaseEntryPointsStayInsideTheProtectionUtility() {
        noClasses()
                .that().resideOutsideOfPackage("..testsupport..")
                .should().callMethod(DriverManager.class, "getConnection", String.class)
                .check(testClasses);
        noClasses()
                .that().resideOutsideOfPackage("..testsupport..")
                .should().callMethod(
                        DriverManager.class,
                        "getConnection",
                        String.class,
                        String.class,
                        String.class)
                .check(testClasses);
        noClasses()
                .that().resideOutsideOfPackage("..testsupport..")
                .should().callMethod(Flyway.class, "configure")
                .check(testClasses);
    }
}
