package ca.tyny.urlshortener.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test method as verifying a specific living specification requirement.
 *
 * <p>This annotation is the single source of truth for the requirement↔test traceability. The
 * {@code check-living-spec.sh} gate reads only this annotation — {@code @DisplayName} conventions
 * are cosmetic only and not read by the gate.
 *
 * <p>Usage:
 *
 * <pre>
 * @TracesRequirement("REQ-RATE-001")
 * @Test
 * @DisplayName("REQ_RATE_001: should reject requests exceeding rate limit with 429")
 * void shouldRejectExcessiveRequests() { ... }
 * </pre>
 *
 * @see <a href="https://alistairmavin.com/ears/">EARS notation</a>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface TracesRequirement {

  /**
   * The requirement ID in EARS format: {@code REQ-<COMPONENT>-<NNN>}. Example: {@code REQ-RATE-001}
   */
  String value();
}
