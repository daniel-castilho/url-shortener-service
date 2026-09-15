package ca.tyny.urlshortener.infra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import ca.tyny.urlshortener.core.annotation.TracesRequirement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Verifies the AUTH filter maps the {@code role} claim (or its absence) to Spring authorities
 * (REQ-AUTH-011): ADMIN claim → ROLE_ADMIN, USER claim or absent → ROLE_USER.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

  @Mock private UserDetailsService userDetailsService;

  private JwtTokenProvider provider;
  private JwtAuthenticationFilter filter;

  @BeforeEach
  void setUp() {
    provider = new JwtTokenProvider();
    ReflectionTestUtils.setField(provider, "jwtSecret", "strong-test-secret-0123456789012345678");
    ReflectionTestUtils.setField(provider, "jwtExpirationMs", 60_000L);
    ReflectionTestUtils.setField(provider, "jwtRefreshExpirationMs", 600_000L);
    provider.validateSecret();
    filter = new JwtAuthenticationFilter(provider, userDetailsService);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @TracesRequirement("REQ-AUTH-011")
  void mapsAdminClaimToRoleAdminAuthority() throws Exception {
    String jwt = provider.generateToken("admin@example.com", "ADMIN");
    stubUserDetails("admin@example.com");

    MockHttpServletRequest request = buildBearerRequest(jwt);
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilterInternal(request, response, chain);

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    assertThat(auth).isNotNull();
    assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
    assertThat(auth.getPrincipal()).isInstanceOf(UserDetails.class);
    assertThat(((UserDetails) auth.getPrincipal()).getUsername()).isEqualTo("admin@example.com");
  }

  @Test
  @TracesRequirement("REQ-AUTH-011")
  void mapsExplicitUserClaimToRoleUserAuthority() throws Exception {
    String jwt = provider.generateToken("user@example.com", "USER");
    stubUserDetails("user@example.com");

    MockHttpServletRequest request = buildBearerRequest(jwt);
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilterInternal(request, response, chain);

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    assertThat(auth).isNotNull();
    assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
  }

  @Test
  @TracesRequirement("REQ-AUTH-011")
  void mapsLegacyTokenWithNoClaimToRoleUserAuthority() throws Exception {
    String jwt = provider.generateToken("legacy@example.com", null);
    stubUserDetails("legacy@example.com");

    MockHttpServletRequest request = buildBearerRequest(jwt);
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilterInternal(request, response, chain);

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    assertThat(auth).isNotNull();
    assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
  }

  private void stubUserDetails(String email) {
    UserDetails ud =
        User.withUsername(email)
            .password("ignored")
            .authorities(List.of(new SimpleGrantedAuthority("ROLE_STUB")))
            .build();
    when(userDetailsService.loadUserByUsername(email)).thenReturn(ud);
  }

  private MockHttpServletRequest buildBearerRequest(String jwt) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/urls");
    request.addHeader("Authorization", "Bearer " + jwt);
    return request;
  }
}
