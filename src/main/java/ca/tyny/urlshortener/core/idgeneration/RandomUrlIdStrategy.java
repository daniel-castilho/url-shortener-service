package ca.tyny.urlshortener.core.idgeneration;

import ca.tyny.urlshortener.core.ports.outgoing.IdGeneratorPort;

/**
 * Generates random Base62 short codes for URLs without custom aliases.
 *
 * <p>This strategy is selected when no custom alias is provided (null or blank).
 * It delegates to {@link IdGeneratorPort} which produces cryptographically secure
 * random Base62 codes using {@link java.security.SecureRandom}.</p>
 *
 * <p><b>Collision handling:</b> The strategy relies on the underlying
 * {@link IdGeneratorPort} implementation to handle collisions via bounded retry
 * on unique key constraint violation (see {@link ca.tyny.urlshortener.core.service.UrlShortenerService#saveWithCollisionRetry}).</p>
 *
 * <p><b>Thread safety:</b> This class is stateless and thread-safe.</p>
 *
 * @see UrlIdGenerationStrategy
 * @see IdGeneratorPort
 * @see Base62CodeGenerator
 */
public class RandomUrlIdStrategy implements UrlIdGenerationStrategy {

  private final IdGeneratorPort idGenerator;

  public RandomUrlIdStrategy(IdGeneratorPort idGenerator) {
    this.idGenerator = idGenerator;
  }

  @Override
  public boolean supports(String customAlias) {
    return customAlias == null || customAlias.isBlank();
  }

  @Override
  public String generateId(String customAlias, String userId) {
    return idGenerator.generateId();
  }
}
