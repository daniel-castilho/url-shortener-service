package ca.tyny.urlshortener.infra.adapter.output.security;

import ca.tyny.urlshortener.core.ports.outgoing.VerificationTokenPort;
import ca.tyny.urlshortener.infra.config.properties.DomainProperties;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Generates DNS verification values from a cryptographically secure random source, prefixed so the
 * record is recognizable (e.g. {@code url-shortener-verify=<hex>}).
 */
@Component
public class SecureVerificationTokenProvider implements VerificationTokenPort {

  private static final int TOKEN_BYTES = 16;

  private final SecureRandom secureRandom = new SecureRandom();
  private final String prefix;

  public SecureVerificationTokenProvider(DomainProperties properties) {
    this.prefix = properties.verificationPrefix();
  }

  @Override
  public String generateToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    secureRandom.nextBytes(bytes);
    return prefix + HexFormat.of().formatHex(bytes);
  }
}
