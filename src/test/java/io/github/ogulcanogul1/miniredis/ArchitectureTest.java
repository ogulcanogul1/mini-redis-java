package io.github.ogulcanogul1.miniredis;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;

/** Package layers (PLAN §2.3). Access is transitive: an upper layer may use every lower layer. */
@AnalyzeClasses(packages = "io.github.ogulcanogul1.miniredis", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "io.github.ogulcanogul1.miniredis";

    @ArchTest
    static final ArchRule layers = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true)
            .layer("Root")
            .definedBy(ROOT)
            .layer("Server")
            .definedBy(ROOT + ".server..")
            .layer("Net")
            .definedBy(ROOT + ".net..")
            .layer("Protocol")
            .definedBy(ROOT + ".protocol..")
            .layer("Commands")
            .definedBy(ROOT + ".commands..")
            .layer("Storage")
            .definedBy(ROOT + ".storage..")
            .layer("Persistence")
            .definedBy(ROOT + ".persistence..")
            .layer("Ds")
            .definedBy(ROOT + ".ds..")
            .layer("Concurrency")
            .definedBy(ROOT + ".concurrency..")
            .layer("Util")
            .definedBy(ROOT + ".util..")
            .whereLayer("Root")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("Server")
            .mayOnlyBeAccessedByLayers("Root")
            .whereLayer("Net")
            .mayOnlyBeAccessedByLayers("Root", "Server")
            .whereLayer("Persistence")
            .mayOnlyBeAccessedByLayers("Root", "Server")
            .whereLayer("Protocol")
            .mayOnlyBeAccessedByLayers("Root", "Server", "Net", "Persistence")
            .whereLayer("Commands")
            .mayOnlyBeAccessedByLayers("Root", "Server", "Net", "Protocol", "Persistence")
            .whereLayer("Storage")
            .mayOnlyBeAccessedByLayers("Root", "Server", "Net", "Protocol", "Commands", "Persistence")
            .whereLayer("Ds")
            .mayOnlyBeAccessedByLayers("Root", "Server", "Net", "Protocol", "Commands", "Storage", "Persistence")
            .whereLayer("Concurrency")
            .mayOnlyBeAccessedByLayers("Root", "Server", "Net", "Protocol", "Commands", "Storage", "Persistence")
            .whereLayer("Util")
            .mayOnlyBeAccessedByLayers("Root", "Server", "Net", "Protocol", "Commands", "Storage", "Persistence");

    // ds, util and concurrency depend on nothing in the project, not even on each other.
    @ArchTest
    static final ArchRule dsIsIndependent = independent("ds");

    @ArchTest
    static final ArchRule utilIsIndependent = independent("util");

    @ArchTest
    static final ArchRule concurrencyIsIndependent = independent("concurrency");

    // K12: no console output and no printStackTrace in library code. Main (the root package)
    // may print its version: that is CLI output, not logging.
    @ArchTest
    static final ArchRule noStandardStreams = noClasses()
            .that()
            .resideInAPackage(ROOT + ".*..")
            .should(GeneralCodingRules.ACCESS_STANDARD_STREAMS)
            .allowEmptyShould(true);

    // Match by name: javac records the receiver's static type (e.g. IOException) as the owner.
    @ArchTest
    static final ArchRule noPrintStackTrace = noClasses()
            .should()
            .callMethodWhere(target(name("printStackTrace")))
            .allowEmptyShould(true);

    // K12: library and layer code uses only the SLF4J API; only server configures Logback.
    @ArchTest
    static final ArchRule onlyServerMayAccessLogback = noClasses()
            .that()
            .resideOutsideOfPackage(ROOT + ".server..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("ch.qos.logback..")
            .allowEmptyShould(true);

    private static ArchRule independent(String pkg) {
        return noClasses()
                .that()
                .resideInAPackage(ROOT + "." + pkg + "..")
                .should()
                .dependOnClassesThat()
                .resideOutsideOfPackages(ROOT + "." + pkg + "..", "java..")
                .allowEmptyShould(true);
    }
}
