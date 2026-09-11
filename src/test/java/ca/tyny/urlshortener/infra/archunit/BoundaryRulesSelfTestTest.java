package ca.tyny.urlshortener.infra.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Self-test for {@link BoundaryRulesTest} — the same discipline as {@code
 * scripts/check-boundaries.sh --self-test}: a gate that cannot be seen failing might be passing
 * vacuously. Plants a synthetic violating class (in-memory, no source file) and asserts the
 * boundary rule reports it, then asserts the same rule accepts a synthetic clean class.
 */
class BoundaryRulesSelfTestTest {

  /** Touches an infra type and a Spring type — a planted Rule 1 violation. */
  @SuppressWarnings("unused")
  private static final class PlantedViolation {
    private final Object infraComponent =
        new ca.tyny.urlshortener.infra.security.RequestCorrelationFilter();
  }

  /** Plain JDK-only class — must pass every boundary rule. */
  private static final class Clean {
    int add(int a, int b) {
      return a + b;
    }
  }

  @Test
  @DisplayName("Boundary rule flags a planted core->infra violation")
  void ruleCatchesPlantedViolation() {
    JavaClasses classes = new ClassFileImporter().importClasses(PlantedViolation.class);

    assertThrows(
        AssertionError.class,
        () ->
            noClasses().should().dependOnClassesThat().resideInAPackage("..infra..").check(classes),
        "boundary rule must flag the planted infra dependency");
  }

  @Test
  @DisplayName("Boundary rule accepts a clean class")
  void ruleAcceptsCleanClass() {
    JavaClasses classes = new ClassFileImporter().importClasses(Clean.class);

    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..infra..", "org.springframework..")
        .check(classes);
  }
}
