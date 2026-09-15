package ca.tyny.urlshortener.core.ports.outgoing;

/**
 * Outbound port answering whether an email is a product ADMIN account.
 *
 * <p>Backed by the {@code app.admin-emails} configuration list (env {@code APP_ADMIN_EMAILS});
 * there is no {@code role} field in the database (ADR 0011 D1). The contract is intentionally small
 * so the domain layer never imports configuration properties bound to Spring.
 */
public interface AdminEmailPort {

  /**
   * Returns {@code true} when the given email is listed as an admin email. Matching is
   * case-insensitive against the normalized (trim + lowercase) configured list.
   *
   * @param email the user email to test
   */
  boolean isAdminEmail(String email);
}
