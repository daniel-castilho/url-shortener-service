package ca.tyny.urlshortener.infra.security;

import static org.assertj.core.api.Assertions.assertThat;

import ca.tyny.urlshortener.core.annotation.TracesRequirement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * TokenPort contract for the role claim (REQ-AUTH-011): present on access tokens, absent = legacy.
 */
class JwtTokenAdapterTest {

  private JwtTokenProvider provider;
  private JwtTokenAdapter adapter;

  @BeforeEach
  void setUp() {
    provider = new JwtTokenProvider();
    ReflectionTestUtils.setField(provider, "jwtSecret", "strong-test-secret-0123456789012345678");
    ReflectionTestUtils.setField(provider, "jwtExpirationMs", 60_000L);
    ReflectionTestUtils.setField(provider, "jwtRefreshExpirationMs", 600_000L);
    provider.validateSecret();
    adapter = new JwtTokenAdapter(provider);
  }

  @Test
  @TracesRequirement("REQ-AUTH-011")
  void generatesAccessTokenWithRoleClaimPresent() {
    String token = adapter.generateToken("admin@example.com", "ADMIN");

    assertThat(adapter.getUsernameFromToken(token)).isEqualTo("admin@example.com");
    assertThat(adapter.validateToken(token)).isTrue();
    assertThat(provider.getRoleFromToken(token)).isEqualTo("ADMIN");
    assertThat(provider.getUsernameFromToken(token)).isEqualTo("admin@example.com");
  }

  @Test
  @TracesRequirement("REQ-AUTH-011")
  void accessTokenWithoutRoleClaimReadsNullLegacyShape() {
    String token = adapter.generateToken("user@example.com", null);

    assertThat(provider.getRoleFromToken(token)).isNull();
    assertThat(provider.getUsernameFromToken(token)).isEqualTo("user@example.com");
  }
}
