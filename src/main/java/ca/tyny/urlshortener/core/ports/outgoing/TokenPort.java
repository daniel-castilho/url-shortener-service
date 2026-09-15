package ca.tyny.urlshortener.core.ports.outgoing;

/**
 * Port for token generation and validation operations. This interface defines the contract for JWT
 * token management.
 */
public interface TokenPort {

  /**
   * Generate an access token for the given email carrying the {@code role} claim.
   *
   * <p>The role claim is derived from the configured admin email list (ADR 0011 D1) and lives only
   * in the access token — the refresh token carries no claim (minimal surface). A legacy token
   * issued without the claim is interpreted as {@code ROLE_USER} by the auth filter.
   *
   * @param email the email to generate a token for
   * @param role the role to embed as the {@code role} claim ({@code "ADMIN"} or {@code "USER"})
   * @return the generated token
   */
  String generateToken(String email, String role);

  /**
   * Generate a refresh token for the given email.
   *
   * @param email the email to generate a refresh token for
   * @return the generated refresh token
   */
  String generateRefreshToken(String email);

  /**
   * Validate a token.
   *
   * @param token the token to validate
   * @return true if the token is valid, false otherwise
   */
  boolean validateToken(String token);

  /**
   * Extract the username (email) from a token.
   *
   * @param token the token to extract the username from
   * @return the username (email) from the token
   */
  String getUsernameFromToken(String token);
}
