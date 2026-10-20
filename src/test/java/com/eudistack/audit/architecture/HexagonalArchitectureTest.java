package com.eudistack.audit.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Layer boundaries of {@code hexagonal-discipline}: the domain imports no framework, the application no
 * infrastructure nor web, and nothing reaches into infrastructure from the inside.
 */
class HexagonalArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.eudistack.audit");

    @Test
    void domain_dependsOnNoFramework() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta..", "javax.sql..", "java.sql..", "com.nimbusds..",
                        "software.amazon..", "io.micrometer..", "org.flywaydb..", "com.zaxxer..",
                        "com.eudistack.audit.application..", "com.eudistack.audit.infrastructure..")
                .check(CLASSES);
    }

    @Test
    void application_dependsOnNoInfrastructureNorWeb() {
        noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.eudistack.audit.infrastructure..", "org.springframework..", "jakarta.servlet..",
                        "java.sql..", "software.amazon..", "com.nimbusds..")
                .check(CLASSES);
    }
}
