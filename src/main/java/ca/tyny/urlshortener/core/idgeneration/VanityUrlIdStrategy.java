package ca.tyny.urlshortener.core.idgeneration;

import ca.tyny.urlshortener.core.model.User;
import ca.tyny.urlshortener.core.ports.outgoing.UrlRepositoryPort;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;

/**
 * Generates custom vanity URL codes for authenticated users.
 *
 * <p>This strategy is selected when a custom alias is provided (non-null, non-blank).
 * It enforces the following constraints:</p>
 *
 * <ul>
 *   <li><b>Authentication required:</b> User must be authenticated (non-null userId)</li>
 *   <li><b>Plan validation:</b> User must have a subscription plan that allows vanity URLs
 *       (checked via {@link User#canCreateVanityUrls()})</li>
 *   <li><b>Format validation:</b> Alias must match regex {@code ^[a-zA-Z0-9-_]+$}
 *       (alphanumeric plus hyphen and underscore only)</li>
 *   <li><b>Uniqueness:</b> Alias must not already exist in the URL repository
 *       (atomic check via {@link UrlRepositoryPort#existsById})</li>
 * </ul>
 *
 * <p><b>Error handling:</b> Throws {@link IllegalArgumentException} with descriptive
 * messages for each validation failure:</p>
 * <ul>
 *   <li>Missing authentication</li>
 *   <li>Plan limit reached or inactive subscription</li>
 *   <li>Invalid character format</li>
 *   <li>Alias already in use</li>
 * </ul>
 *
 * <p><b>Concurrency:</b> Relies on database unique constraint on {@code short_urls._id}
 * for atomicity; the {@code existsById} check is a best-effort pre-check.</p>
 *
 * @see UrlIdGenerationStrategy
 * @see UrlRepositoryPort
 * @see UserRepositoryPort
 * @see User#canCreateVanityUrls()
 */
public class VanityUrlIdStrategy implements UrlIdGenerationStrategy {

  private final UserRepositoryPort userRepository;
  private final UrlRepositoryPort urlRepository;

  public VanityUrlIdStrategy(UserRepositoryPort userRepository, UrlRepositoryPort urlRepository) {
    this.userRepository = userRepository;
    this.urlRepository = urlRepository;
  }

  @Override
  public boolean supports(String customAlias) {
    // Supports when there IS a custom alias
    return customAlias != null && !customAlias.isBlank();
  }

  @Override
  public String generateId(String customAlias, String userId) {
    if (userId == null) {
      throw new IllegalArgumentException("Authentication required for custom alias");
    }

    // Validate User and Plan
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));

    if (!user.canCreateVanityUrls()) {
      throw new IllegalArgumentException(
          "Plan limit reached for vanity URLs or subscription inactive");
    }

    // Validate Alias Format
    if (!customAlias.matches("^[a-zA-Z0-9-_]+$")) {
      throw new IllegalArgumentException("Invalid custom alias format");
    }

    // Validate Alias Availability
    if (urlRepository.existsById(customAlias)) {
      throw new IllegalArgumentException("Custom alias already in use");
    }

    return customAlias;
  }
}
