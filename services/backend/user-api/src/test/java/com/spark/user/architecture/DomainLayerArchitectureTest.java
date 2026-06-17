package com.spark.user.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.spark.user", importOptions = ImportOption.DoNotIncludeTests.class)
class DomainLayerArchitectureTest {
    @ArchTest
    static final ArchRule domainShouldNotDependOnFrameworksOrAdapters = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.persistence..",
                    "javax.persistence..",
                    "io.grpc..",
                    "java.sql..",
                    "..adapter..",
                    "..infrastructure..");
}
