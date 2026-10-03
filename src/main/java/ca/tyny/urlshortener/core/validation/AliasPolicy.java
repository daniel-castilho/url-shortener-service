package ca.tyny.urlshortener.core.validation;

/**
 * Global custom-alias policy shared by the REST boundary (bean validation) and the shorten use
 * case, so the two layers cannot drift apart.
 *
 * <p>The plan-based minimum alias length lives in {@code SubscriptionPlan}; the maximum is
 * plan-independent and lives here. The same cap must be mirrored by the edge and the frontend (see
 * docs/backend-frontend-contract.md) — the backend cap is authoritative, the mirrors exist to fail
 * fast and avoid divergent client-side limits.
 */
public final class AliasPolicy {

  /** Maximum accepted custom-alias length, in characters. */
  public static final int MAX_LENGTH = 64;

  private AliasPolicy() {
    throw new AssertionError("Utility class should not be instantiated");
  }

  /**
   * Throws {@link IllegalArgumentException} when the alias exceeds {@link #MAX_LENGTH}.
   *
   * <p>{@code null} is accepted: an absent alias means an auto-generated code and is not subject to
   * alias rules.
   */
  public static void validateMaxLength(String alias) {
    if (alias != null && alias.length() > MAX_LENGTH) {
      throw new IllegalArgumentException(
          "Custom alias must be at most "
              + MAX_LENGTH
              + " characters (got "
              + alias.length()
              + ")");
    }
  }
}
