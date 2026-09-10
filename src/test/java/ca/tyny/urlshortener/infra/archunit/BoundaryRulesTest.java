package ca.tyny.urlshortener.infra.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Codifies AGENTS.md Rule 1 (Architecture Boundaries) as executable tests. Deliberately redundant
 * with {@code scripts/check-boundaries.sh}: the script fails fast on every push (grep-based, no
 * compile), while these rules run inside {@code ./mvnw test} against the compiled bytecode, so a
 * bypass of the script (e.g. exotic import forms) is still caught by the gate that builds the jar.
 */
@AnalyzeClasses(
    packages = "ca.tyny.urlshortener",
    importOptions = ImportOption.DoNotIncludeTests.class)
class BoundaryRulesTest {

  @ArchTest
  static final ArchRule coreMustNotImportInfra =
      noClasses()
          .that()
          .resideInAPackage("..core..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..infra..")
          .because("core/ is the domain; it must never import infra (AGENTS.md Rule 1)");

  @ArchTest
  static final ArchRule coreMustBeFrameworkFree =
      noClasses()
          .that()
          .resideInAPackage("..core..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "org.mongodb..",
              "org.redisson..",
              "io.jsonwebtoken..",
              "io.micrometer..",
              "lombok..",
              "tools.jackson..",
              "com.fasterxml.jackson..")
          .because(
              "core/ is pure Java: frameworks and IO libraries live behind ports in"
                  + " infra/ (AGENTS.md Rule 1)");
}
